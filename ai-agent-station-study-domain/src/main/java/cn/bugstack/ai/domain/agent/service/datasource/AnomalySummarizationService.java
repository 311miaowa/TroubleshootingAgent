package cn.bugstack.ai.domain.agent.service.datasource;

import cn.bugstack.ai.domain.agent.adapter.port.datasource.DataSourceCapability;
import cn.bugstack.ai.domain.agent.adapter.port.datasource.IDataSourceProvider;
import cn.bugstack.ai.domain.agent.adapter.port.datasource.IDataSourceRegistry;
import cn.bugstack.ai.domain.agent.model.valobj.datasource.EntityMeta;
import cn.bugstack.ai.domain.agent.model.valobj.datasource.LogSearchQuery;
import cn.bugstack.ai.domain.agent.model.valobj.datasource.QueryResult;
import cn.bugstack.ai.domain.agent.model.valobj.datasource.TimeRange;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * 异常归纳服务（M7a）：把"日志原始行"归纳成"结构化现象摘要"
 * <p>
 * <b>为什么要这一层</b>：现在模型拿到的是原始行（几百条），它只能自己"读"出规律——
 * 这既慢又不可靠（同一批数据两次给不同结论）。本层把"统计与判定"收回后端：
 * 归一模板 → 分组计数与占比 → 时间桶分布 → 中位数 + k×IQR 判离群 → 输出**一行 = 一个现象**。
 * <p>
 * <b>它是纯计算层</b>：只通过 {@link DataSourceQueryService} 取数、只做数学，
 * 不引入 LLM、不做因果推断、不写库。所有阈值是常量（见下方 {@code *} 常量），
 * <b>不进模型 schema</b>——防止模型把"多轮试参数"当成排障手段。
 * <p>
 * <b>诚实边界（写进 {@code warnings}，也让模型看见）</b>：
 * <ol>
 *     <li><b>没有正常期基线</b>：只能给窗口内的绝对计数与占比，<b>不能</b>说"比平时高"；</li>
 *     <li><b>小样本不标异常</b>：命中 &lt; {@link #MIN_SAMPLE} 条的现象不标离群（1 条日志不构成现象）；</li>
 *     <li><b>桶数不足不标异常</b>：有效桶 &lt; {@link #MIN_BUCKETS_FOR_OUTLIER} 时 IQR 没有统计意义；</li>
 *     <li><b>归一有损失</b>：不同错误码可能被归为一类（见 {@link LogPatternNormalizer} 的代价说明）。</li>
 * </ol>
 *
 * @author miaowazhongzi
 */
@Slf4j
@Service
public class AnomalySummarizationService {

    /**
     * 归纳结果使用的实体编码（不是真实数据实体，是"现象摘要"这个视图）
     */
    public static final String SUMMARY_ENTITY_CODE = "log_summary";

    /**
     * 时间桶粒度（分钟）：固定 5 分钟，不暴露给模型
     */
    public static final int BUCKET_MINUTES = 5;

    /**
     * 一次归纳最多扫描的原始行数（保护后端与模型上下文；不暴露给模型）
     */
    public static final int MAX_SCAN_ROWS = 500;

    /**
     * 现象组默认/上限条数
     */
    public static final int DEFAULT_LIMIT = 20;

    public static final int MAX_LIMIT = 50;

    /**
     * 离群判定系数：{@code peakCount > median + IQR_K * IQR}
     */
    static final double IQR_K = 3.0d;

    /**
     * 小于该命中数的现象不标离群
     */
    static final int MIN_SAMPLE = 3;

    /**
     * 有效桶数少于该值时不做离群判定（IQR 需要足够的桶才有意义）
     */
    static final int MIN_BUCKETS_FOR_OUTLIER = 5;

    /**
     * 时间/内容/级别/实例字段的候选名（按顺序取第一个存在的列）
     */
    private static final String[] TIME_FIELDS = {"@timestamp", "timestamp", "time", "createTime", "logTime"};

    private static final String[] MESSAGE_FIELDS = {"message", "msg", "content", "log", "body"};

    private static final String[] LEVEL_FIELDS = {"level", "severity", "logLevel", "log_level"};

    private static final String[] CONTAINER_FIELDS = {"application", "container", "app", "service"};

    @Resource
    private IDataSourceRegistry dataSourceRegistry;

    @Resource
    private DataSourceQueryService dataSourceQueryService;

    /**
     * 归纳入口：按关键字检索日志并归纳成现象摘要
     *
     * @param keyword 检索关键字
     * @param range   时间窗（为空时按最近 1 小时，避免无界查询）
     * @param limit   最多返回多少个现象组（≤ {@link #MAX_LIMIT}）
     */
    public QueryResult summarize(String keyword, TimeRange range, int limit) {
        IDataSourceProvider provider = findLogProvider();
        if (provider == null) {
            QueryResult empty = QueryResult.empty(null, SUMMARY_ENTITY_CODE);
            empty.getWarnings().add("没有可用的日志数据源（无 log_search 能力），无法归纳");
            return empty;
        }
        String entityCode = firstEntityCode(provider);
        if (entityCode == null) {
            QueryResult empty = QueryResult.empty(provider.dsId(), SUMMARY_ENTITY_CODE);
            empty.getWarnings().add("日志数据源未暴露可查询实体，无法归纳");
            return empty;
        }

        long start = System.currentTimeMillis();
        QueryResult searched;
        try {
            searched = dataSourceQueryService.search(provider.dsId(), LogSearchQuery.builder()
                    .keyword(keyword)
                    .limit(MAX_SCAN_ROWS)
                    .timeRange(range == null || range.isUnbounded() ? TimeRange.defaultRange() : range)
                    .build());
        } catch (Exception e) {
            log.error("异常归纳取数失败 dsId={}：{}", provider.dsId(), e.getMessage(), e);
            QueryResult failed = QueryResult.empty(provider.dsId(), SUMMARY_ENTITY_CODE);
            failed.getWarnings().add("取数失败：" + e.getMessage());
            return failed;
        }

        QueryResult summary = summarizeRows(provider.dsId(), entityCode,
                searched == null ? List.of() : searched.getRows(), limit);
        if (searched != null && searched.getTotal() > searched.getRows().size()) {
            summary.getWarnings().add("原始命中 " + searched.getTotal() + " 条，本次只归纳了前 "
                    + searched.getRows().size() + " 条（上限 " + MAX_SCAN_ROWS + "）");
        }
        summary.setCostMs(System.currentTimeMillis() - start);
        return summary;
    }

    /**
     * 纯计算：把原始行归纳成"一行 = 一个现象"的 {@link QueryResult}
     * <p>
     * 与取数解耦，便于单测直接用构造数据钉住统计口径（归一/分组/占比/IQR/小样本/rawRef 双通道）。
     *
     * @param dsId       来源数据源
     * @param entityCode 来源实体编码（用于 rawRef 描述覆盖范围）
     * @param rows       原始行（可为 null）
     * @param limit      最多返回多少个现象组
     */
    public QueryResult summarizeRows(String dsId, String entityCode, List<Map<String, Object>> rows, int limit) {
        int maxGroups = limit <= 0 ? DEFAULT_LIMIT : Math.min(limit, MAX_LIMIT);
        QueryResult result = QueryResult.empty(dsId, SUMMARY_ENTITY_CODE);
        result.setColumns(List.of("pattern", "level", "count", "share",
                "firstSeen", "lastSeen", "peakBucket", "peakCount", "outlier"));
        result.getWarnings().add("未提供正常期基线：本结果只含窗口内的绝对计数与占比，"
                + "不能据此判断「比平时更高」或「异常加剧」");

        if (rows == null || rows.isEmpty()) {
            result.getWarnings().add("窗口内 0 条命中，无现象可归纳");
            return result;
        }

        Map<String, Group> groups = new LinkedHashMap<>();
        int unparsableTime = 0;
        int missingMessage = 0;
        Instant minAt = null;
        Instant maxAt = null;

        for (Map<String, Object> row : rows) {
            if (row == null) {
                continue;
            }
            Instant at = RowTimes.parse(pick(row, TIME_FIELDS));
            if (at == null) {
                unparsableTime++;
                continue;
            }
            Object rawMessage = pick(row, MESSAGE_FIELDS);
            if (rawMessage == null) {
                missingMessage++;
            }
            String pattern = LogPatternNormalizer.normalize(rawMessage == null ? null : String.valueOf(rawMessage));
            String level = levelOf(pick(row, LEVEL_FIELDS));
            String key = pattern + '\u0000' + level;

            Group group = groups.computeIfAbsent(key, k -> new Group(pattern, level));
            group.count++;
            group.firstSeen = group.firstSeen == null || at.isBefore(group.firstSeen) ? at : group.firstSeen;
            group.lastSeen = group.lastSeen == null || at.isAfter(group.lastSeen) ? at : group.lastSeen;
            long bucket = bucketKey(at);
            group.buckets.merge(bucket, 1, Integer::sum);
            String rawRef = rawRefOf(row);
            if (rawRef != null && !rawRef.isBlank()) {
                group.rawRefs.add(rawRef);
            }
            if (minAt == null || at.isBefore(minAt)) {
                minAt = at;
            }
            if (maxAt == null || at.isAfter(maxAt)) {
                maxAt = at;
            }
        }

        int totalHits = groups.values().stream().mapToInt(g -> g.count).sum();
        if (totalHits == 0) {
            result.getWarnings().add("窗口内没解析出任何带时间的日志行（时间字段无法识别或缺失）");
            result.getWarnings().add("时间解析失败行数：" + unparsableTime);
            return result;
        }

        List<Group> ordered = new ArrayList<>(groups.values());
        ordered.sort(Comparator.comparingInt((Group g) -> g.count).reversed());
        boolean truncated = ordered.size() > maxGroups;
        if (truncated) {
            ordered = ordered.subList(0, maxGroups);
        }

        int smallSample = 0;
        List<Map<String, Object>> outRows = new ArrayList<>(ordered.size());
        for (Group group : ordered) {
            int peakCount = group.buckets.values().stream().mapToInt(Integer::intValue).max().orElse(0);
            long peakBucket = group.buckets.entrySet().stream()
                    .max(Map.Entry.comparingByValue())
                    .map(Map.Entry::getKey).orElse(0L);
            boolean outlier = isOutlier(group.buckets, peakCount);
            if (group.count < MIN_SAMPLE) {
                // 小样本不标异常：1~2 条日志不构成"现象"，标异常会制造假阳性
                outlier = false;
                smallSample++;
            }

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("pattern", group.pattern);
            out.put("level", group.level);
            out.put("count", group.count);
            out.put("share", round3(group.count / (double) totalHits));
            out.put("firstSeen", RowTimes.formatBucket(group.firstSeen));
            out.put("lastSeen", RowTimes.formatBucket(group.lastSeen));
            out.put("peakBucket", RowTimes.formatBucket(Instant.ofEpochSecond(peakBucket)));
            out.put("peakCount", peakCount);
            out.put("outlier", outlier);
            // 行级回指：只进事件版（toModelJson 会剥掉）
            if (!group.rawRefs.isEmpty()) {
                out.put(QueryResult.RAW_REF_KEY, String.join(" | ", group.rawRefs));
            }
            outRows.add(out);
        }

        result.setRows(outRows);
        result.setTotal(outRows.size());
        result.setTruncated(truncated);
        result.setRawRef(String.format("summary://%s/%s@%s~%s#groupBy=pattern",
                dsId, entityCode, RowTimes.formatBucket(minAt), RowTimes.formatBucket(maxAt)));

        if (truncated) {
            result.getWarnings().add("现象组共 " + groups.size() + " 个，本次只返回计数最高的 " + maxGroups + " 个");
        }
        if (smallSample > 0) {
            result.getWarnings().add("有 " + smallSample + " 个现象组命中 < " + MIN_SAMPLE
                    + " 条，样本量不足，未做离群标记");
        }
        if (unparsableTime > 0) {
            result.getWarnings().add("时间解析失败并被跳过的行数：" + unparsableTime);
        }
        if (missingMessage > 0) {
            result.getWarnings().add("有 " + missingMessage + " 行缺少消息字段，已归为 " + LogPatternNormalizer.UNKNOWN);
        }
        result.getWarnings().add("时间桶粒度固定 " + BUCKET_MINUTES + " 分钟；离群判定为「桶计数 > 中位数 + "
                + (int) IQR_K + "×IQR」，且要求有效桶 ≥ " + MIN_BUCKETS_FOR_OUTLIER);
        return result;
    }

    // ------------------------------------------------------------------ 统计

    /**
     * 桶计数序列是否离群：峰值桶 > 中位数 + k×IQR，且有效桶数达标
     */
    private boolean isOutlier(Map<Long, Integer> buckets, int peakCount) {
        if (buckets == null || buckets.size() < MIN_BUCKETS_FOR_OUTLIER) {
            return false;
        }
        List<Integer> counts = new ArrayList<>(buckets.values());
        counts.sort(Comparator.naturalOrder());
        double median = median(counts);
        double q1 = quantile(counts, 0.25d);
        double q3 = quantile(counts, 0.75d);
        return peakCount > median + IQR_K * (q3 - q1);
    }

    /**
     * 中位数（偶数个取中间两个的均值）
     */
    static double median(List<Integer> sorted) {
        if (sorted == null || sorted.isEmpty()) {
            return 0d;
        }
        int n = sorted.size();
        if (n % 2 == 1) {
            return sorted.get(n / 2);
        }
        return (sorted.get(n / 2 - 1) + sorted.get(n / 2)) / 2.0d;
    }

    /**
     * 分位数（线性插值法，与常见 IQR 口径一致）
     */
    static double quantile(List<Integer> sorted, double q) {
        if (sorted == null || sorted.isEmpty()) {
            return 0d;
        }
        if (sorted.size() == 1) {
            return sorted.get(0);
        }
        double pos = q * (sorted.size() - 1);
        int lower = (int) Math.floor(pos);
        int upper = (int) Math.ceil(pos);
        if (lower == upper) {
            return sorted.get(lower);
        }
        double weight = pos - lower;
        return sorted.get(lower) * (1 - weight) + sorted.get(upper) * weight;
    }

    private double round3(double value) {
        return Math.round(value * 1000d) / 1000d;
    }

    private long bucketKey(Instant at) {
        long bucketSeconds = BUCKET_MINUTES * 60L;
        return at.getEpochSecond() / bucketSeconds * bucketSeconds;
    }

    // ------------------------------------------------------------------ 取值

    private IDataSourceProvider findLogProvider() {
        for (IDataSourceProvider provider : dataSourceRegistry.listByTenant(null)) {
            Set<DataSourceCapability> capabilities = provider.capabilities();
            if (capabilities != null && capabilities.contains(DataSourceCapability.LOG_SEARCH)) {
                return provider;
            }
        }
        return null;
    }

    private String firstEntityCode(IDataSourceProvider provider) {
        List<EntityMeta> entities = provider.listEntities();
        if (entities == null || entities.isEmpty()) {
            return null;
        }
        return entities.get(0).getEntityCode();
    }

    /**
     * 按候选名取第一个存在的列（行内 key 不固定：不同索引/映射字段名不同）
     */
    private Object pick(Map<String, Object> row, String[] candidates) {
        for (String candidate : candidates) {
            Object value = row.get(candidate);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private String levelOf(Object rawLevel) {
        if (rawLevel == null) {
            return "UNKNOWN";
        }
        String level = String.valueOf(rawLevel).trim().toUpperCase();
        return level.isEmpty() ? "UNKNOWN" : level;
    }

    private String rawRefOf(Map<String, Object> row) {
        Object value = row.get(QueryResult.RAW_REF_KEY);
        return value == null ? null : String.valueOf(value);
    }

    /**
     * 归纳过程中的一个现象组
     */
    private static final class Group {
        private final String pattern;
        private final String level;
        private final Map<Long, Integer> buckets = new TreeMap<>();
        private final List<String> rawRefs = new ArrayList<>();
        private int count;
        private Instant firstSeen;
        private Instant lastSeen;

        private Group(String pattern, String level) {
            this.pattern = pattern;
            this.level = level;
        }
    }

}
