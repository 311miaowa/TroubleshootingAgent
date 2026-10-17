package cn.bugstack.ai.domain.agent.service.datasource;

import cn.bugstack.ai.domain.agent.adapter.port.datasource.DataSourceCapability;
import cn.bugstack.ai.domain.agent.adapter.port.datasource.IDataSourceProvider;
import cn.bugstack.ai.domain.agent.adapter.port.datasource.IDataSourceRegistry;
import cn.bugstack.ai.domain.agent.model.valobj.datasource.BaselineComparison;
import cn.bugstack.ai.domain.agent.model.valobj.datasource.EntityMeta;
import cn.bugstack.ai.domain.agent.model.valobj.datasource.FaultWindow;
import cn.bugstack.ai.domain.agent.model.valobj.datasource.LogSearchQuery;
import cn.bugstack.ai.domain.agent.model.valobj.datasource.QueryResult;
import cn.bugstack.ai.domain.agent.model.valobj.datasource.TimeRange;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * 故障期 vs 对称正常基线 对比服务（M8）
 * <p>
 * <b>它解决什么</b>：只有故障期数据时，"限流 WARN 出现 20 次"是多是少无法判断——缺正常期参照就没有判定依据。
 * 本服务把 {@link FaultWindow} 算出的三段（故障期 / 前基线 / 后基线）**分别查一遍**，按时间桶并排输出，
 * 让"故障期 37 条、前基线 2 条"这种**机械事实**直接可见。
 * <p>
 * <b>纪律（M8 决策 1/3/5）</b>：
 * <ol>
 *     <li>窗口由框架注入（{@link FaultWindow}），<b>不进工具 schema</b>——与 {@code tenantId} 同一条纪律；</li>
 *     <li><b>只做机械对比，不做因果判断</b>：不输出"所以是限流导致"，只给计数与速率；</li>
 *     <li><b>不给调参口</b>：桶粒度、扫描上限、最小基线长度都是常量。</li>
 * </ol>
 * <p>
 * <b>降级如实</b>：任一段查询失败/不可用时进 {@code warnings} 并跳过该段，绝不编造数据。
 *
 * @author miaowazhongzi
 */
@Slf4j
@Service
public class FaultWindowComparisonService {

    /**
     * 对比结果的实体编码（不是真实数据实体，是"故障期 vs 基线"这个视图）
     */
    public static final String SUMMARY_ENTITY_CODE = "fault_baseline";

    /**
     * 时间桶粒度（分钟），不暴露给模型
     */
    public static final int BUCKET_MINUTES = 5;

    /**
     * 每段最多扫描的原始行数（保护后端，不暴露给模型）
     */
    public static final int MAX_SCAN_ROWS = 500;

    /**
     * 输出行上限（含各段"合计"行）
     */
    public static final int DEFAULT_LIMIT = 100;

    public static final int MAX_LIMIT = 200;

    /**
     * "合计"行的标记（放在 bucketStart 列，UI 会原样显示）
     */
    public static final String TOTAL_LABEL = "合计";

    private static final String[] TIME_FIELDS = {"@timestamp", "timestamp", "time", "createTime", "logTime"};

    @Resource
    private IDataSourceRegistry dataSourceRegistry;

    @Resource
    private DataSourceQueryService dataSourceQueryService;

    /**
     * 执行对比：故障期 + 前/后基线，按 phase 分桶输出
     *
     * @param keyword 检索关键字
     * @param window  故障窗（来自本次请求；为 null 表示调用方没提供 → 如实告警、返回空结果）
     * @param now     当前时间（由调用方传入，便于单测）
     * @param limit   输出行上限（≤ {@link #MAX_LIMIT}）
     */
    public QueryResult compare(String keyword, FaultWindow window, Instant now, int limit) {
        QueryResult result = QueryResult.empty(null, SUMMARY_ENTITY_CODE);
        result.setColumns(List.of("phase", "bucketStart", "count", "countPerMinute"));

        if (window == null || !window.isUsable()) {
            result.getWarnings().add("本次请求未提供故障窗（faultStart 缺失或无法解析），无法做故障期/基线对比；"
                    + "请在上游把 faultStart/faultEnd（可选 prevFaultEnd/nextFaultStart）带上");
            return result;
        }

        IDataSourceProvider provider = findLogProvider();
        if (provider == null) {
            result.getWarnings().add("没有可用的日志数据源（无 log_search 能力），无法做对比");
            return result;
        }
        String dsId = provider.dsId();
        result.setDsId(dsId);

        int maxRows = limit <= 0 ? DEFAULT_LIMIT : Math.min(limit, MAX_LIMIT);
        List<Map<String, Object>> rows = new ArrayList<>();
        boolean truncated = false;
        int totalHits = 0;

        for (BaselineComparison comparison : window.comparisons(now)) {
            String phase = comparison.getPhase();
            TimeRange range = comparison.getRange();
            List<Map<String, Object>> rawRows;
            try {
                rawRows = search(dsId, keyword, range);
            } catch (Exception e) {
                log.warn("故障窗对比取数失败 phase={}：{}", phase, e.getMessage());
                result.getWarnings().add(phase + " 取数失败：" + e.getMessage() + "（该段不产出对比）");
                continue;
            }
            if (rawRows == null) {
                result.getWarnings().add(phase + " 取数失败（返回空引用），该段不产出对比");
                continue;
            }

            Map<Long, Integer> buckets = new TreeMap<>();
            int phaseTotal = 0;
            for (Map<String, Object> row : rawRows) {
                Instant at = row == null ? null : RowTimes.parse(pick(row, TIME_FIELDS));
                if (at == null) {
                    continue;
                }
                buckets.merge(bucketKey(at), 1, Integer::sum);
                phaseTotal++;
            }
            totalHits += phaseTotal;
            long windowMinutes = Math.max(1L, Duration.between(range.getStart(), range.getEnd()).toMinutes());

            for (Map.Entry<Long, Integer> entry : buckets.entrySet()) {
                if (rows.size() >= maxRows) {
                    truncated = true;
                    break;
                }
                rows.add(row(phase, RowTimes.formatBucket(Instant.ofEpochSecond(entry.getKey())),
                        entry.getValue(), round2(entry.getValue() / (double) BUCKET_MINUTES)));
            }
            if (rows.size() < maxRows) {
                rows.add(row(phase, TOTAL_LABEL, phaseTotal, round2(phaseTotal / (double) windowMinutes)));
            } else {
                truncated = true;
            }
            result.getWarnings().add(phase + " 命中 " + phaseTotal + " 条（" + RowTimes.formatBucket(range.getStart())
                    + " ~ " + RowTimes.formatBucket(range.getEnd()) + "，约 " + round2(phaseTotal / (double) windowMinutes)
                    + " 条/分钟）");
        }

        result.setRows(rows);
        result.setTotal(totalHits);
        result.setTruncated(truncated);
        result.setRawRef("faultwindow://" + dsId + "/" + SUMMARY_ENTITY_CODE + "#phases="
                + window.comparisons(now).size() + ",bucket=" + BUCKET_MINUTES + "min");
        result.getWarnings().add("只做机械对比：fault 与 baseline 的计数差异**不代表因果关系**，请结合日志内容判断");
        result.getWarnings().addAll(window.warnings(now));
        if (truncated) {
            result.getWarnings().add("输出行数已达上限 " + maxRows + "，其余桶被截断（可提高 limit 或缩短窗口）");
        }
        return result;
    }

    // ------------------------------------------------------------------ 内部

    private List<Map<String, Object>> search(String dsId, String keyword, TimeRange range) {
        QueryResult searched = dataSourceQueryService.search(dsId, LogSearchQuery.builder()
                .keyword(keyword)
                .limit(MAX_SCAN_ROWS)
                .timeRange(range)
                .build());
        return searched == null ? null : searched.getRows();
    }

    private IDataSourceProvider findLogProvider() {
        for (IDataSourceProvider provider : dataSourceRegistry.listByTenant(null)) {
            Set<DataSourceCapability> capabilities = provider.capabilities();
            if (capabilities != null && capabilities.contains(DataSourceCapability.LOG_SEARCH)) {
                return provider;
            }
        }
        return null;
    }

    private long bucketKey(Instant at) {
        long bucketSeconds = BUCKET_MINUTES * 60L;
        return at.getEpochSecond() / bucketSeconds * bucketSeconds;
    }

    private Map<String, Object> row(String phase, String bucketStart, int count, double perMinute) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("phase", phase);
        row.put("bucketStart", bucketStart);
        row.put("count", count);
        row.put("countPerMinute", perMinute);
        return row;
    }

    private double round2(double value) {
        return Math.round(value * 100d) / 100d;
    }

    private Object pick(Map<String, Object> row, String[] candidates) {
        for (String candidate : candidates) {
            Object value = row.get(candidate);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    /**
     * 便于单测：实体编码（当前不做实体侧对比，保留给后续"故障期订单数对比"）
     */
    protected String entityCodeOf(IDataSourceProvider provider) {
        List<EntityMeta> entities = provider.listEntities();
        return entities == null || entities.isEmpty() ? null : entities.get(0).getEntityCode();
    }

}
