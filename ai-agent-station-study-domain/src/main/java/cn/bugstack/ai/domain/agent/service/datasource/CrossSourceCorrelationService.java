package cn.bugstack.ai.domain.agent.service.datasource;

import cn.bugstack.ai.domain.agent.adapter.port.datasource.DataSourceCapability;
import cn.bugstack.ai.domain.agent.adapter.port.datasource.IDataSourceProvider;
import cn.bugstack.ai.domain.agent.adapter.port.datasource.IDataSourceRegistry;
import cn.bugstack.ai.domain.agent.adapter.port.datasource.IEntityQueryProvider;
import cn.bugstack.ai.domain.agent.adapter.port.datasource.ILogSearchProvider;
import cn.bugstack.ai.domain.agent.model.valobj.datasource.EntityMeta;
import cn.bugstack.ai.domain.agent.model.valobj.datasource.EntityQuery;
import cn.bugstack.ai.domain.agent.model.valobj.datasource.LogSearchQuery;
import cn.bugstack.ai.domain.agent.model.valobj.datasource.QueryResult;
import cn.bugstack.ai.domain.agent.model.valobj.datasource.TimeRange;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 跨源关联：把「日志时间线」与「订单时间线」对齐到同一根时间轴（P1）
 * <p>
 * <b>要解决的问题</b>：像"下单失败是否与限流有关"这类问题，天然需要<b>同时</b>看两侧数据并把它们摆在
 * 同一根时间轴上；此前 Agent 只能分别调用 {@code query_logs} / {@code query_orders}，
 * 把"哪条日志和哪笔订单落在同一分钟"留给模型自己在长文本里比对——这正是它最容易算错的地方。
 * <p>
 * <b>定位</b>：本类是一层<b>编排</b>，不是新的数据源实现：
 * <ul>
 *     <li>通过 {@link IDataSourceRegistry} 按<b>能力</b>发现两侧来源（{@code log_search} / {@code entity_query}），
 *         <b>不硬编码 dsId</b>——将来接第三个源、或把订单源换成别的实现，这里都不用改；</li>
 *     <li>通过 {@link DataSourceQueryService} 现有门面查询（租户注入、耗时记录、异常兜底都在那里），
 *         <b>不改 Provider SPI</b>；</li>
 *     <li>产出仍是 {@link QueryResult}：一行 = 一个时间桶，于是 M3 的证据面板、rawRef 回指、
 *         模型版/事件版 JSON 剥离等既有机制<b>全部直接复用</b>。</li>
 * </ul>
 * <p>
 * <b>纪律（沿用 M3/M4）</b>：
 * <ol>
 *     <li><b>只做机械对齐，不做因果推断</b>——输出"这个时间桶里同时有日志与订单"这一事实，
 *         "是否相关"由模型基于它去判断；</li>
 *     <li><b>一侧不可用必须如实说</b>：某侧未注册/查询失败/0 命中，都会写进 {@code warnings}
 *         （沿用 M4 的经验：不能把"连不上"静默成"没有数据"）；</li>
 *     <li><b>时间归一集中在一处</b>（{@link RowTimes}），无时区值一律按 Asia/Shanghai 解释；</li>
 *     <li>解析不出时间的行<b>跳过并计数告警</b>，不猜默认时间。</li>
 * </ol>
 *
 * @author miaowazhongzi
 */
@Slf4j
@Service
public class CrossSourceCorrelationService {

    /**
     * 关联结果的 dsId（合成来源，不代表任何真实数据源）
     */
    public static final String CORRELATE_DS_ID = "cross_source";

    /**
     * 关联结果的 entityCode
     */
    public static final String CORRELATE_ENTITY_CODE = "timeline";

    /**
     * 时间桶：列名（顺序即 UI 渲染顺序）
     */
    private static final List<String> COLUMNS =
            List.of("bucketStart", "logCount", "orderCount", "logSummary", "orderSummary");

    private static final int DEFAULT_BUCKET_MINUTES = 1;

    private static final int DEFAULT_PER_SOURCE_LIMIT = 50;

    private static final int MAX_PER_SOURCE_LIMIT = 200;

    /**
     * 摘要字段的最大长度：对齐表是"给人/给模型看线索"的，不搬运整行原文（原文有 rawRef 可回指）
     */
    private static final int MAX_SUMMARY_CHARS = 140;

    private static final String LOG_SIDE_TIME_FALLBACK = "@timestamp";

    private static final String ORDER_SIDE_TIME_FALLBACK = "create_time";

    @Resource
    private IDataSourceRegistry dataSourceRegistry;

    @Resource
    private DataSourceQueryService dataSourceQueryService;

    /**
     * 对齐两侧时间线，返回"时间桶"表
     *
     * @param keyword         日志侧检索关键字（必填：ES 侧不接受空关键字，避免全量扫描）
     * @param timeRange       时间窗（缺省/无界时收敛为最近 1 小时）
     * @param bucketMinutes   时间桶粒度（分钟），默认 1
     * @param perSourceLimit  每侧最多取多少条，默认 50，上限 200
     * @return 一行一个时间桶的 {@link QueryResult}；无法关联时返回带 {@code warnings} 的空结果（不抛异常）
     */
    public QueryResult correlate(String keyword, TimeRange timeRange, int bucketMinutes, int perSourceLimit) {
        return correlate(keyword, timeRange, bucketMinutes, perSourceLimit, null);
    }

    /**
     * 同上，但可指定与日志对齐的<b>实体</b>（P1-2：接入第三个数据源后 entity_query 源不再唯一）
     * <p>
     * 为什么需要它：此前"同类多源"时只能按 dsId 排序取第一个——第三个源加入后，那就成了静默的任意选择。
     * 现在模型可用 {@code entityCode} 明确指定对齐哪一侧（如 {@code payments} 支付流水）；不指定时仍取第一个，
     * 并把"还有哪些实体源可选"写进告警，让模型知道有得选。
     *
     * @param entityCode 期望对齐的实体编码（如 orders / payments）；为 null/空时取第一个 entity_query 源
     */
    public QueryResult correlate(String keyword, TimeRange timeRange, int bucketMinutes, int perSourceLimit,
                                 String entityCode) {
        long start = System.currentTimeMillis();
        TimeRange range = (timeRange == null || timeRange.isUnbounded()) ? TimeRange.defaultRange() : timeRange;
        int bucket = bucketMinutes > 0 ? bucketMinutes : DEFAULT_BUCKET_MINUTES;
        int limit = perSourceLimit > 0 ? Math.min(perSourceLimit, MAX_PER_SOURCE_LIMIT) : DEFAULT_PER_SOURCE_LIMIT;

        QueryResult out = QueryResult.builder()
                .dsId(CORRELATE_DS_ID)
                .entityCode(CORRELATE_ENTITY_CODE)
                .columns(new ArrayList<>(COLUMNS))
                .rows(new ArrayList<>())
                .build();

        if (keyword == null || keyword.isBlank()) {
            // ES 侧 keyword 必填：这里提前拦下并说明原因，绝不退化成"全量扫描"或"静默空结果"
            out.getWarnings().add("缺少 keyword：日志侧检索必须带关键字（空关键字会被 ES Provider 拒绝，也避免全量扫描）");
            out.setCostMs(System.currentTimeMillis() - start);
            return out;
        }

        IDataSourceProvider logProvider = firstWithCapability(DataSourceCapability.LOG_SEARCH);
        List<IDataSourceProvider> entityProviders = allWithCapability(DataSourceCapability.ENTITY_QUERY);
        IDataSourceProvider orderProvider = pickEntityProvider(entityProviders, entityCode);
        if (orderProvider != null && entityCode != null && !entityCode.isBlank()) {
            String actual = entityCodeOf(orderProvider, "entity_query", null);
            if (!entityCode.equals(actual)) {
                out.getWarnings().add("指定的实体 " + entityCode + " 未在已注册的实体源中找到，"
                        + "已回退到 dsId=" + orderProvider.dsId() + "（实体 " + actual + "）");
            }
        }

        if (logProvider == null && orderProvider == null) {
            out.getWarnings().add("没有可用的数据源：注册表里既没有 log_search 也没有 entity_query 来源");
            out.setCostMs(System.currentTimeMillis() - start);
            return out;
        }

        // ---- 日志侧 ----
        List<Entry> logEntries = new ArrayList<>();
        int logSkipped = 0;
        if (logProvider == null) {
            out.getWarnings().add("日志侧不可用：注册表中没有 log_search 来源，本次只返回实体时间线");
        } else if (!(logProvider instanceof ILogSearchProvider)) {
            out.getWarnings().add("日志侧不可用：dsId=" + logProvider.dsId() + " 声明了 log_search 但未实现 ILogSearchProvider");
        } else {
            QueryResult logs = safeSearch(logProvider, keyword, range, limit);
            String timeField = entityTimeField(logProvider, "log_search", LOG_SIDE_TIME_FALLBACK);
            logSkipped = collect(logs, timeField, Side.LOG, logEntries);
            if (logs.getWarnings() != null && !logs.getWarnings().isEmpty()) {
                out.getWarnings().add("日志侧（" + logProvider.dsId() + "）查询告警：" + logs.getWarnings().get(0));
            }
            if (logs.getRows() == null || logs.getRows().isEmpty()) {
                out.getWarnings().add("日志侧（" + logProvider.dsId() + "）在窗口内 0 条命中：不排除时间窗过窄或关键字不匹配");
            }
            if (logs.isTruncated()) {
                out.setTruncated(true);
            }
        }

        // ---- 实体侧 ----
        List<Entry> orderEntries = new ArrayList<>();
        int orderSkipped = 0;
        if (orderProvider == null) {
            out.getWarnings().add("实体侧不可用：注册表中没有 entity_query 来源，本次只返回日志时间线");
        } else if (!(orderProvider instanceof IEntityQueryProvider)) {
            out.getWarnings().add("实体侧不可用：dsId=" + orderProvider.dsId() + " 声明了 entity_query 但未实现 IEntityQueryProvider");
        } else {
            String alignedEntity = entityCodeOf(orderProvider, "entity_query", null);
            if (alignedEntity == null) {
                out.getWarnings().add("实体侧不可用：dsId=" + orderProvider.dsId() + " 没有 entity_query 实体配置");
            } else {
                QueryResult orders = safeQuery(orderProvider, alignedEntity, range, limit);
                String timeField = entityTimeField(orderProvider, "entity_query", ORDER_SIDE_TIME_FALLBACK);
                orderSkipped = collect(orders, timeField, Side.ORDER, orderEntries);
                if (orders.getWarnings() != null && !orders.getWarnings().isEmpty()) {
                    out.getWarnings().add("实体侧（" + orderProvider.dsId() + "）查询告警：" + orders.getWarnings().get(0));
                }
                if (orders.getRows() == null || orders.getRows().isEmpty()) {
                    out.getWarnings().add("实体侧（" + orderProvider.dsId() + "）在窗口内 0 条命中：不排除时间窗过窄");
                }
                if (orders.isTruncated()) {
                    out.setTruncated(true);
                }
            }
        }

        // ---- 归一 + 分桶 ----
        Map<Long, List<Entry>> buckets = new TreeMap<>();
        for (Entry entry : logEntries) {
            buckets.computeIfAbsent(bucketKey(entry.at, bucket), key -> new ArrayList<>()).add(entry);
        }
        for (Entry entry : orderEntries) {
            buckets.computeIfAbsent(bucketKey(entry.at, bucket), key -> new ArrayList<>()).add(entry);
        }
        for (Map.Entry<Long, List<Entry>> bucketEntry : buckets.entrySet()) {
            out.getRows().add(toRow(bucketEntry.getKey(), bucketEntry.getValue()));
        }
        out.setTotal(out.getRows().size());
        out.setCostMs(System.currentTimeMillis() - start);
        out.setRawRef(buildRawRef(logProvider, orderProvider, range));

        // 来源说明放在最前，让人/模型一眼看清"这次对齐用了谁、桶多大、什么时区"
        out.getWarnings().add(0, "已对齐：" + describeSide("日志", logProvider, logEntries.size())
                + " + " + describeSide("实体", orderProvider, orderEntries.size())
                + "；桶粒度 " + bucket + " 分钟；时间按 " + RowTimes.BIZ_ZONE + " 解释"
                + describeOtherEntitySources(entityProviders, orderProvider));
        if (logSkipped > 0) {
            out.getWarnings().add("日志侧有 " + logSkipped + " 行时间字段无法解析，已跳过（未猜默认时间）");
        }
        if (orderSkipped > 0) {
            out.getWarnings().add("实体侧有 " + orderSkipped + " 行时间字段无法解析，已跳过（未猜默认时间）");
        }

        log.info("跨源对齐完成 logs={}({}) entity={}({}) buckets={} costMs={}",
                logProvider == null ? "-" : logProvider.dsId(), logEntries.size(),
                orderProvider == null ? "-" : orderProvider.dsId(), orderEntries.size(),
                out.getRows().size(), out.getCostMs());
        return out;
    }

    // ------------------------------------------------------------------ 查询

    private QueryResult safeSearch(IDataSourceProvider provider, String keyword, TimeRange range, int limit) {
        try {
            QueryResult result = dataSourceQueryService.search(provider.dsId(),
                    LogSearchQuery.builder().keyword(keyword).limit(limit).timeRange(range).build());
            return result == null ? QueryResult.empty(provider.dsId(), null) : result;
        } catch (Exception e) {
            log.warn("跨源对齐：日志侧查询失败 dsId={}：{}", provider.dsId(), e.getMessage());
            QueryResult failed = QueryResult.empty(provider.dsId(), null);
            failed.getWarnings().add("日志侧查询失败：" + e.getMessage());
            return failed;
        }
    }

    private QueryResult safeQuery(IDataSourceProvider provider, String entityCode, TimeRange range, int limit) {
        try {
            QueryResult result = dataSourceQueryService.query(provider.dsId(),
                    EntityQuery.builder().entityCode(entityCode).limit(limit).timeRange(range).build());
            return result == null ? QueryResult.empty(provider.dsId(), entityCode) : result;
        } catch (Exception e) {
            log.warn("跨源对齐：实体侧查询失败 dsId={}：{}", provider.dsId(), e.getMessage());
            QueryResult failed = QueryResult.empty(provider.dsId(), entityCode);
            failed.getWarnings().add("实体侧查询失败：" + e.getMessage());
            return failed;
        }
    }

    // ------------------------------------------------------------------ 归一 / 分桶

    private int collect(QueryResult result, String timeField, Side side, List<Entry> target) {
        if (result == null || result.getRows() == null || result.getRows().isEmpty()) {
            return 0;
        }
        int skipped = 0;
        for (Map<String, Object> row : result.getRows()) {
            if (row == null) {
                continue;
            }
            Instant at = RowTimes.parse(row.get(timeField));
            if (at == null) {
                skipped++;
                continue;
            }
            target.add(new Entry(at, side, summarize(row, side), rawRefOf(row)));
        }
        return skipped;
    }

    private long bucketKey(Instant at, int bucketMinutes) {
        long bucketSeconds = bucketMinutes * 60L;
        return at.getEpochSecond() / bucketSeconds * bucketSeconds;
    }

    private Map<String, Object> toRow(long bucketEpochSecond, List<Entry> entries) {
        List<String> logSummaries = new ArrayList<>();
        List<String> orderSummaries = new ArrayList<>();
        List<String> rawRefs = new ArrayList<>();
        for (Entry entry : entries) {
            if (entry.side == Side.LOG) {
                logSummaries.add(entry.summary);
            } else {
                orderSummaries.add(entry.summary);
            }
            if (entry.rawRef != null && !entry.rawRef.isBlank()) {
                rawRefs.add(entry.rawRef);
            }
        }
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("bucketStart", RowTimes.formatBucket(Instant.ofEpochSecond(bucketEpochSecond)));
        row.put("logCount", logSummaries.size());
        row.put("orderCount", orderSummaries.size());
        row.put("logSummary", join(logSummaries));
        row.put("orderSummary", join(orderSummaries));
        // 行级回指：一个桶里可能同时有多条日志与多笔订单，用分隔符全部带上（证据面板逐条回指原文）
        if (!rawRefs.isEmpty()) {
            row.put(QueryResult.RAW_REF_KEY, String.join(" | ", rawRefs));
        }
        return row;
    }

    private String summarize(Map<String, Object> row, Side side) {
        if (side == Side.LOG) {
            return truncate(joinValues(row, "level", "application", "message"));
        }
        return truncate(joinValues(row, "order_no", "status", "fail_reason", "amount"));
    }

    private String joinValues(Map<String, Object> row, String... keys) {
        List<String> parts = new ArrayList<>();
        for (String key : keys) {
            Object value = row.get(key);
            if (value != null && !String.valueOf(value).isBlank()) {
                parts.add(String.valueOf(value));
            }
        }
        return String.join(" · ", parts);
    }

    private String join(List<String> values) {
        return values.isEmpty() ? "" : String.join(" ⟂ ", values);
    }

    private String truncate(String text) {
        if (text == null) {
            return "";
        }
        return text.length() <= MAX_SUMMARY_CHARS ? text : text.substring(0, MAX_SUMMARY_CHARS) + "…";
    }

    private String rawRefOf(Map<String, Object> row) {
        Object value = row.get(QueryResult.RAW_REF_KEY);
        return value == null ? null : String.valueOf(value);
    }

    // ------------------------------------------------------------------ 来源发现 / 说明

    /**
     * 按能力列出候选来源，按 dsId 排序（保证"多个同类源"时选择稳定、可复现）
     */
    private List<IDataSourceProvider> allWithCapability(DataSourceCapability capability) {
        List<IDataSourceProvider> providers = dataSourceRegistry.listByTenant(null);
        if (providers == null || providers.isEmpty()) {
            return List.of();
        }
        return providers.stream()
                .filter(p -> p.capabilities() != null && p.capabilities().contains(capability))
                .sorted(Comparator.comparing(IDataSourceProvider::dsId,
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
    }

    private IDataSourceProvider firstWithCapability(DataSourceCapability capability) {
        List<IDataSourceProvider> providers = allWithCapability(capability);
        return providers.isEmpty() ? null : providers.get(0);
    }

    /**
     * 选出用于对齐的实体源：优先按 entityCode 精确匹配；匹配不到（或未指定）则取第一个
     */
    private IDataSourceProvider pickEntityProvider(List<IDataSourceProvider> candidates, String entityCode) {
        if (candidates == null || candidates.isEmpty()) {
            return null;
        }
        if (entityCode == null || entityCode.isBlank()) {
            return candidates.get(0);
        }
        for (IDataSourceProvider provider : candidates) {
            if (entityCode.equals(entityCodeOf(provider, "entity_query", null))) {
                return provider;
            }
        }
        return candidates.get(0);
    }

    private String entityCodeOf(IDataSourceProvider provider, String capability, String fallback) {
        EntityMeta meta = firstEntity(provider, capability);
        if (meta == null) {
            return fallback;
        }
        return meta.getEntityCode() == null ? fallback : meta.getEntityCode();
    }

    private String entityTimeField(IDataSourceProvider provider, String capability, String fallback) {
        EntityMeta meta = firstEntity(provider, capability);
        if (meta == null || meta.getTimeField() == null || meta.getTimeField().isBlank()) {
            return fallback;
        }
        return meta.getTimeField();
    }

    private EntityMeta firstEntity(IDataSourceProvider provider, String capability) {
        List<EntityMeta> entities;
        try {
            entities = provider.listEntities();
        } catch (Exception e) {
            log.warn("跨源对齐：读取实体清单失败 dsId={}：{}", provider.dsId(), e.getMessage());
            return null;
        }
        if (entities == null) {
            return null;
        }
        return entities.stream()
                .filter(entity -> entity != null && capability.equals(entity.getCapability()))
                .findFirst()
                .orElse(null);
    }

    private String describeSide(String label, IDataSourceProvider provider, int count) {
        if (provider == null) {
            return label + "=未接入";
        }
        return label + "@" + provider.dsId() + "（" + count + " 条）";
    }

    /**
     * 列出"还有哪些实体源可选"，让模型知道可以用 {@code entityCode} 换一侧对齐
     * （P1-2：接入第二个 entity_query 源之后，默认取第一个不再是不言自明的事）
     */
    private String describeOtherEntitySources(List<IDataSourceProvider> providers, IDataSourceProvider used) {
        if (providers == null || providers.size() <= 1) {
            return "";
        }
        List<String> others = new ArrayList<>();
        for (IDataSourceProvider provider : providers) {
            if (provider == used) {
                continue;
            }
            others.add(provider.dsId() + "(" + entityCodeOf(provider, "entity_query", "?") + ")");
        }
        return others.isEmpty() ? "" : "；其它可选实体源：" + String.join("、", others);
    }

    private String buildRawRef(IDataSourceProvider logProvider, IDataSourceProvider orderProvider, TimeRange range) {
        return "correlate://"
                + (logProvider == null ? "-" : logProvider.dsId())
                + "+" + (orderProvider == null ? "-" : orderProvider.dsId())
                + "@" + range.getStart() + "~" + range.getEnd();
    }

    /**
     * 对齐口径：日志 or 订单
     */
    private enum Side {
        LOG,
        ORDER
    }

    /**
     * 时间轴上的一个条目（已归一为 {@link Instant}）
     */
    private static final class Entry {

        private final Instant at;

        private final Side side;

        private final String summary;

        private final String rawRef;

        private Entry(Instant at, Side side, String summary, String rawRef) {
            this.at = at;
            this.side = side;
            this.summary = summary;
            this.rawRef = rawRef;
        }

    }

}
