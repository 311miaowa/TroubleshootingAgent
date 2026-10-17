package cn.bugstack.ai.test.p1;

import cn.bugstack.ai.domain.agent.adapter.port.datasource.DataSourceCapability;
import cn.bugstack.ai.domain.agent.adapter.port.datasource.IDataSourceProvider;
import cn.bugstack.ai.domain.agent.adapter.port.datasource.IDataSourceRegistry;
import cn.bugstack.ai.domain.agent.adapter.port.datasource.IEntityQueryProvider;
import cn.bugstack.ai.domain.agent.adapter.port.datasource.ILogSearchProvider;
import cn.bugstack.ai.domain.agent.model.valobj.datasource.EntityMeta;
import cn.bugstack.ai.domain.agent.model.valobj.datasource.EntityQuery;
import cn.bugstack.ai.domain.agent.model.valobj.datasource.HealthStatus;
import cn.bugstack.ai.domain.agent.model.valobj.datasource.LogSearchQuery;
import cn.bugstack.ai.domain.agent.model.valobj.datasource.QueryResult;
import cn.bugstack.ai.domain.agent.model.valobj.datasource.TimeRange;
import cn.bugstack.ai.domain.agent.service.datasource.CrossSourceCorrelationService;
import cn.bugstack.ai.domain.agent.service.datasource.DataSourceQueryService;
import cn.bugstack.ai.domain.agent.service.datasource.RowTimes;
import com.alibaba.fastjson.JSON;
import org.junit.Assert;
import org.junit.Test;

import java.lang.reflect.Field;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * P1 跨源关联单测：时间归一 / 时间桶对齐 / 缺失侧诚实标注 / 证据版与模型版 JSON 的取舍。
 * <p>
 * <b>不依赖 Spring、数据库与网络</b>：两侧 Provider 只负责"能力发现"（桩），数据由查询门面桩提供。
 * 用例的重点不是"能不能跑通"，而是把几个<b>容易悄悄错</b>的点钉住：
 * <ul>
 *     <li>日志时间（带 {@code +08:00} 的 ISO 字符串）与订单时间（无时区 datetime / epoch 毫秒）
 *         必须归一到同一根时间轴——差 8 小时就完全对不上；</li>
 *     <li>某一侧不可用/查询失败时，结果里必须有明确告警，<b>不能</b>表现成"没有关联"；</li>
 *     <li>行级 {@code _rawRef} 只给证据层，模型版 JSON 里必须没有。</li>
 * </ul>
 *
 * @author miaowazhongzi
 */
public class P1CrossSourceCorrelationTest {

    /** 日志时间：带 +08:00 偏移的 ISO（= 02:30:00Z） */
    private static final String LOG_TIME = "2026-03-27T10:30:00+08:00";

    /** 同一时刻的订单时间：数据库风格的裸 datetime（按 Asia/Shanghai 解释） */
    private static final String ORDER_TIME = "2026-03-27 10:30:30";

    private static final Instant T0230Z = Instant.parse("2026-03-27T02:30:00Z");

    /** 2026-03-27 16:20:00+08:00 —— 真实库里 ai_order 的一行 */
    private static final Instant T1620 = Instant.parse("2026-03-27T08:20:00Z");

    private static final TimeRange RANGE = TimeRange.of(
            Instant.parse("2026-03-26T16:00:00Z"), Instant.parse("2026-03-27T15:59:59Z"));

    // ------------------------------------------------------------------ 时间归一

    @Test
    public void rowTimes_parsesAllShapesSeenInPractice() {
        Assert.assertEquals(T0230Z, RowTimes.parse("2026-03-27T10:30:00+08:00"));
        Assert.assertEquals(T0230Z, RowTimes.parse("2026-03-27T02:30:00Z"));
        Assert.assertEquals(T0230Z, RowTimes.parse("2026-03-27T10:30:00"));
        Assert.assertEquals(T0230Z, RowTimes.parse("2026-03-27 10:30:00"));
        Assert.assertEquals(T0230Z, RowTimes.parse("2026-03-27 10:30"));
        Assert.assertEquals(T0230Z, RowTimes.parse(LocalDateTime.of(2026, 3, 27, 10, 30)));
        Assert.assertEquals(T0230Z, RowTimes.parse(OffsetDateTime.parse("2026-03-27T10:30:00+08:00")));
        Assert.assertEquals(T0230Z, RowTimes.parse(Timestamp.from(T0230Z)));
        // epoch：秒与毫秒都要认（≥1e11 视为毫秒）
        Assert.assertEquals(T1620, RowTimes.parse(1774599600L));
        Assert.assertEquals(T1620, RowTimes.parse(1774599600000L));
        Assert.assertEquals(T1620, RowTimes.parse("1774599600000"));
    }

    @Test
    public void rowTimes_returnsNullInsteadOfGuessing() {
        Assert.assertNull(RowTimes.parse(null));
        Assert.assertNull(RowTimes.parse(""));
        Assert.assertNull(RowTimes.parse("  "));
        Assert.assertNull(RowTimes.parse("not-a-time"));
        Assert.assertNull(RowTimes.parse(0L));
        Assert.assertNull(RowTimes.parse(-1L));
    }

    @Test
    public void rowTimes_formatsBucketInBizZone() {
        Assert.assertEquals("2026-03-27 10:30:00", RowTimes.formatBucket(T0230Z));
        Assert.assertEquals("2026-03-27 16:20:00", RowTimes.formatBucket(T1620));
    }

    // ------------------------------------------------------------------ 对齐

    @Test
    public void correlate_sameMinuteLogAndOrder_landInOneBucket() {
        QueryResult result = service(
                logProvider("ds_log_es"),
                orderProvider("ds_order_mysql"),
                stub(logResult(row(LOG_TIME, "WARN", "group-buy-market", "用户访问拼团项目被限流")),
                        orderResult(orderRow(ORDER_TIME, "NO20260327001", "FAILED", "PAY_TIMEOUT", "199.00"))))
                .correlate("限流", RANGE, 1, 50);

        Assert.assertEquals("同一分钟应落在一个桶里", 1, result.getRows().size());
        Map<String, Object> bucket = result.getRows().get(0);
        Assert.assertEquals("2026-03-27 10:30:00", bucket.get("bucketStart"));
        Assert.assertEquals(1, bucket.get("logCount"));
        Assert.assertEquals(1, bucket.get("orderCount"));
        Assert.assertTrue(String.valueOf(bucket.get("logSummary")).contains("限流"));
        Assert.assertTrue(String.valueOf(bucket.get("orderSummary")).contains("PAY_TIMEOUT"));
        Assert.assertTrue("桶级 _rawRef 应带上两侧原文引用",
                String.valueOf(bucket.get(QueryResult.RAW_REF_KEY)).contains("order_no=NO20260327001"));
        Assert.assertTrue(result.getRawRef().startsWith("correlate://ds_log_es+ds_order_mysql@"));
    }

    /** 时区口径：无时区的订单 datetime 必须按 Asia/Shanghai 解释，否则会与日志差 8 小时 */
    @Test
    public void correlate_zonelessOrderTime_isInterpretedInBizZone() {
        QueryResult result = service(
                logProvider("ds_log_es"),
                orderProvider("ds_order_mysql"),
                stub(logResult(row(LOG_TIME, "WARN", "app", "limit")),
                        orderResult(orderRow("2026-03-27 10:30:59", "NO1", "FAILED", null, "1.00"))))
                .correlate("限流", RANGE, 1, 50);

        Assert.assertEquals("裸 datetime 按 +08:00 解释后应与日志同桶", 1, result.getRows().size());
        Assert.assertEquals(1, result.getRows().get(0).get("orderCount"));
    }

    @Test
    public void correlate_differentBuckets_areListedSeparately_ascending() {
        QueryResult result = service(
                logProvider("ds_log_es"),
                orderProvider("ds_order_mysql"),
                stub(logResult(row("2026-03-27T10:31:00+08:00", "WARN", "app", "later"),
                                row(LOG_TIME, "WARN", "app", "earlier")),
                        orderResult(orderRow("2026-03-27 10:33:00", "NO9", "FAILED", "X", "1"))))
                .correlate("限流", RANGE, 1, 50);

        Assert.assertEquals(3, result.getRows().size());
        Assert.assertEquals("2026-03-27 10:30:00", result.getRows().get(0).get("bucketStart"));
        Assert.assertEquals("2026-03-27 10:31:00", result.getRows().get(1).get("bucketStart"));
        Assert.assertEquals("2026-03-27 10:33:00", result.getRows().get(2).get("bucketStart"));
        Assert.assertEquals(3, result.getTotal());
    }

    @Test
    public void correlate_bucketMinutes_groupsWiderWindow() {
        QueryResult result = service(
                logProvider("ds_log_es"),
                orderProvider("ds_order_mysql"),
                stub(logResult(row("2026-03-27T10:32:00+08:00", "WARN", "app", "a"),
                                row("2026-03-27T10:34:00+08:00", "WARN", "app", "b")),
                        orderResult(orderRow("2026-03-27 10:34:30", "NO2", "FAILED", "Y", "2"))))
                .correlate("限流", RANGE, 5, 50);

        Assert.assertEquals("5 分钟桶内应合并为一行", 1, result.getRows().size());
        Assert.assertEquals("2026-03-27 10:30:00", result.getRows().get(0).get("bucketStart"));
        Assert.assertEquals(2, result.getRows().get(0).get("logCount"));
        Assert.assertEquals(1, result.getRows().get(0).get("orderCount"));
    }

    // ------------------------------------------------------------------ 诚实标注

    @Test
    public void correlate_blankKeyword_refusesInsteadOfScanning() {
        StubQueryService stub = stub(logResult(), orderResult());
        QueryResult result = service(logProvider("ds_log_es"), orderProvider("ds_order_mysql"), stub)
                .correlate("   ", RANGE, 1, 50);

        Assert.assertTrue(result.getRows().isEmpty());
        Assert.assertEquals("不应发起任何查询", 0, stub.searchCalls + stub.queryCalls);
        Assert.assertTrue("必须说明为什么拒绝：" + result.getWarnings(),
                result.getWarnings().stream().anyMatch(w -> w.contains("缺少 keyword")));
    }

    @Test
    public void correlate_orderSideUnavailable_saysSo_andStillReturnsLogTimeline() {
        QueryResult result = service(
                logProvider("ds_log_es"),
                null,
                stub(logResult(row(LOG_TIME, "WARN", "app", "limit")), orderResult()))
                .correlate("限流", RANGE, 1, 50);

        Assert.assertEquals("日志侧仍要出结果", 1, result.getRows().size());
        Assert.assertTrue("必须明确说明实体侧不可用：" + result.getWarnings(),
                result.getWarnings().stream().anyMatch(w -> w.contains("实体侧不可用") && w.contains("entity_query")));
        Assert.assertTrue("来源说明里实体侧应标为未接入：" + result.getWarnings(),
                result.getWarnings().stream().anyMatch(w -> w.contains("实体=未接入")));
    }

    @Test
    public void correlate_orderQueryFails_reportsWarning_notNoCorrelation() {
        StubQueryService stub = stub(logResult(row(LOG_TIME, "WARN", "app", "limit")), orderResult());
        stub.queryThrows = new IllegalStateException("Failed to obtain JDBC Connection");

        QueryResult result = service(logProvider("ds_log_es"), orderProvider("ds_order_mysql"), stub)
                .correlate("限流", RANGE, 1, 50);

        Assert.assertEquals("一侧坏了不能把整条结果丢掉", 1, result.getRows().size());
        Assert.assertTrue("必须把实体侧失败如实写进告警：" + result.getWarnings(),
                result.getWarnings().stream().anyMatch(
                        w -> w.contains("实体侧") && w.contains("Failed to obtain JDBC Connection")));
    }

    @Test
    public void correlate_zeroHits_isCalledOut_asCaveat() {
        QueryResult result = service(logProvider("ds_log_es"), orderProvider("ds_order_mysql"),
                stub(logResult(), orderResult()))
                .correlate("限流", RANGE, 1, 50);

        Assert.assertTrue(result.getRows().isEmpty());
        Assert.assertEquals("两侧 0 命中都要说清是「窗口内没有」，而不是「无关」：" + result.getWarnings(),
                2, result.getWarnings().stream().filter(w -> w.contains("0 条命中")).count());
    }

    @Test
    public void correlate_unparsableTimeIsSkippedAndCounted() {
        QueryResult result = service(
                logProvider("ds_log_es"),
                orderProvider("ds_order_mysql"),
                stub(logResult(row(LOG_TIME, "WARN", "app", "ok"), row("这不是时间", "WARN", "app", "bad")),
                        orderResult()))
                .correlate("限流", RANGE, 1, 50);

        Assert.assertEquals(1, result.getRows().size());
        Assert.assertEquals(1, result.getRows().get(0).get("logCount"));
        Assert.assertTrue("跳过的行必须计数告警：" + result.getWarnings(),
                result.getWarnings().stream().anyMatch(w -> w.contains("无法解析") && w.contains("1 行")));
    }

    @Test
    public void correlate_noSourcesAtAll_returnsWarning() {
        QueryResult result = service(null, null, stub(logResult(), orderResult()))
                .correlate("限流", RANGE, 1, 50);
        Assert.assertTrue(result.getWarnings().stream().anyMatch(w -> w.contains("没有可用的数据源")));
    }

    // ------------------------------------------------------------------ 多实体源（P1-2）

    /**
     * P1-2：接入第二个 entity_query 源（payment）后，entityCode 必须能指定对齐哪一侧——
     * 否则"取第一个"就成了一次静默的任意选择。
     */
    @Test
    public void correlate_entityCodePicksMatchingEntitySource() {
        StubQueryService stub = stub(logResult(row(LOG_TIME, "WARN", "app", "limit")),
                orderResult(orderRow(ORDER_TIME, "NO20260327001", "TIMEOUT", "GW_TIMEOUT", "199.00")));
        CrossSourceCorrelationService service = serviceWithProviders(
                List.of(logProvider("ds_log_es"),
                        entityProvider("ds_order_mysql", "orders"),
                        entityProvider("ds_pg_payment", "payments")),
                stub);

        QueryResult result = service.correlate("限流", RANGE, 1, 50, "payments");

        Assert.assertEquals("应路由到支付流水源", "ds_pg_payment", stub.lastQueryDsId);
        Assert.assertEquals("payments", stub.lastQueryEntityCode);
        Assert.assertEquals(1, result.getRows().size());
        Assert.assertTrue("来源说明应指出用了哪个实体源：" + result.getWarnings(),
                result.getWarnings().get(0).contains("实体@ds_pg_payment"));
        Assert.assertTrue("应告知还有别的实体源可选：" + result.getWarnings(),
                result.getWarnings().get(0).contains("其它可选实体源") && result.getWarnings().get(0).contains("orders"));
    }

    @Test
    public void correlate_unknownEntityCode_fallsBackWithWarning() {
        StubQueryService stub = stub(logResult(row(LOG_TIME, "WARN", "app", "limit")),
                orderResult(orderRow(ORDER_TIME, "NO1", "FAILED", "X", "1")));
        CrossSourceCorrelationService service = serviceWithProviders(
                List.of(logProvider("ds_log_es"),
                        entityProvider("ds_order_mysql", "orders"),
                        entityProvider("ds_pg_payment", "payments")),
                stub);

        QueryResult result = service.correlate("限流", RANGE, 1, 50, "not_exist");

        Assert.assertEquals("匹配不到时回退到第一个实体源", "ds_order_mysql", stub.lastQueryDsId);
        Assert.assertTrue("回退必须告警（不能静默换源）：" + result.getWarnings(),
                result.getWarnings().stream().anyMatch(w -> w.contains("not_exist") && w.contains("已回退")));
    }

    /** 不指定 entityCode 时行为与 P1 一致：取 dsId 排序第一个 */
    @Test
    public void correlate_withoutEntityCode_keepsFirstEntitySource() {
        StubQueryService stub = stub(logResult(row(LOG_TIME, "WARN", "app", "limit")),
                orderResult(orderRow(ORDER_TIME, "NO1", "FAILED", "X", "1")));
        CrossSourceCorrelationService service = serviceWithProviders(
                List.of(logProvider("ds_log_es"),
                        entityProvider("ds_order_mysql", "orders"),
                        entityProvider("ds_pg_payment", "payments")),
                stub);

        service.correlate("限流", RANGE, 1, 50);

        Assert.assertEquals("ds_order_mysql", stub.lastQueryDsId);
    }

    // ------------------------------------------------------------------ 证据 / 模型版

    @Test
    public void modelJson_stripsRawRefs_butEventJsonKeepsThem() {
        QueryResult result = service(
                logProvider("ds_log_es"),
                orderProvider("ds_order_mysql"),
                stub(logResult(row(LOG_TIME, "WARN", "app", "limit")),
                        orderResult(orderRow(ORDER_TIME, "NO1", "FAILED", "PAY_TIMEOUT", "199.00"))))
                .correlate("限流", RANGE, 1, 50);

        String modelJson = result.toModelJson();
        Assert.assertFalse("模型版不得含 _rawRef", modelJson.contains("_rawRef"));
        Assert.assertFalse("模型版不得含 correlate:// 指针", modelJson.contains("correlate://"));
        Assert.assertTrue("模型版仍要有对齐结果本身", modelJson.contains("bucketStart"));

        String eventJson = JSON.toJSONString(result);
        Assert.assertTrue("事件版（给证据面板）必须保留 rawRef", eventJson.contains("correlate://"));
        Assert.assertTrue("事件版保留行级回指", eventJson.contains("_rawRef"));
    }

    // ------------------------------------------------------------------ 测试脚手架

    private CrossSourceCorrelationService service(IDataSourceProvider logProvider,
                                                 IDataSourceProvider orderProvider,
                                                 DataSourceQueryService queryService) {
        List<IDataSourceProvider> providers = new ArrayList<>();
        if (logProvider != null) {
            providers.add(logProvider);
        }
        if (orderProvider != null) {
            providers.add(orderProvider);
        }
        return serviceWithProviders(providers, queryService);
    }

    private CrossSourceCorrelationService serviceWithProviders(List<IDataSourceProvider> providers,
                                                              DataSourceQueryService queryService) {
        CrossSourceCorrelationService service = new CrossSourceCorrelationService();
        inject(service, "dataSourceRegistry", registry(providers));
        inject(service, "dataSourceQueryService", queryService);
        return service;
    }

    /** 只负责"能力发现"的日志源桩（数据由查询门面桩提供） */
    private IDataSourceProvider logProvider(String dsId) {
        return new ILogSearchProvider() {
            @Override
            public QueryResult search(LogSearchQuery query) {
                throw new UnsupportedOperationException("数据应由查询门面桩提供");
            }

            @Override
            public String dsId() {
                return dsId;
            }

            @Override
            public String dsType() {
                return "elasticsearch";
            }

            @Override
            public Set<DataSourceCapability> capabilities() {
                return Set.of(DataSourceCapability.ENTITY_META, DataSourceCapability.LOG_SEARCH);
            }

            @Override
            public HealthStatus healthCheck() {
                return HealthStatus.up();
            }

            @Override
            public List<EntityMeta> listEntities() {
                return List.of(EntityMeta.builder().dsId(dsId).entityCode("logs").entityName("应用日志")
                        .capability("log_search").timeField("@timestamp").build());
            }

            @Override
            public void close() {
            }
        };
    }

    /** 只负责"能力发现"的实体源桩（数据由查询门面桩提供） */
    private IDataSourceProvider orderProvider(String dsId) {
        return entityProvider(dsId, "orders");
    }

    private IDataSourceProvider entityProvider(String dsId, String entityCode) {
        return new IEntityQueryProvider() {
            @Override
            public QueryResult query(EntityQuery query) {
                throw new UnsupportedOperationException("数据应由查询门面桩提供");
            }

            @Override
            public String dsId() {
                return dsId;
            }

            @Override
            public String dsType() {
                return dsId.contains("pg") ? "postgres" : "mysql";
            }

            @Override
            public Set<DataSourceCapability> capabilities() {
                return Set.of(DataSourceCapability.ENTITY_META, DataSourceCapability.ENTITY_QUERY);
            }

            @Override
            public HealthStatus healthCheck() {
                return HealthStatus.up();
            }

            @Override
            public List<EntityMeta> listEntities() {
                return List.of(EntityMeta.builder().dsId(dsId).entityCode(entityCode).entityName(entityCode)
                        .capability("entity_query").timeField("create_time").build());
            }

            @Override
            public void close() {
            }
        };
    }

    private IDataSourceRegistry registry(List<IDataSourceProvider> providers) {
        return new IDataSourceRegistry() {
            @Override
            public void register(IDataSourceProvider provider) {
                throw new UnsupportedOperationException();
            }

            @Override
            public void unregister(String dsId) {
                throw new UnsupportedOperationException();
            }

            @Override
            public Optional<IDataSourceProvider> get(String dsId) {
                return providers.stream().filter(p -> p.dsId().equals(dsId)).findFirst();
            }

            @Override
            public List<IDataSourceProvider> listByTenant(String tenantId) {
                return new ArrayList<>(providers);
            }
        };
    }

    private StubQueryService stub(QueryResult logs, QueryResult orders) {
        StubQueryService stub = new StubQueryService();
        stub.logResult = logs;
        stub.orderResult = orders;
        return stub;
    }

    @SafeVarargs
    private final QueryResult logResult(Map<String, Object>... rows) {
        QueryResult result = QueryResult.empty("ds_log_es", "logs");
        for (Map<String, Object> row : rows) {
            result.getRows().add(row);
        }
        result.setTotal(result.getRows().size());
        result.setColumns(List.of("level", "application", "message", "@timestamp"));
        result.setRawRef("es://ds_log_es/logs/group-buy-market-log-*@window");
        return result;
    }

    @SafeVarargs
    private final QueryResult orderResult(Map<String, Object>... rows) {
        QueryResult result = QueryResult.empty("ds_order_mysql", "orders");
        for (Map<String, Object> row : rows) {
            result.getRows().add(row);
        }
        result.setTotal(result.getRows().size());
        result.setColumns(List.of("order_no", "status", "fail_reason", "amount", "create_time"));
        result.setRawRef("mysql://ds_order_mysql/orders/ai_order@window");
        return result;
    }

    private Map<String, Object> row(String time, String level, String application, String message) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("level", level);
        row.put("application", application);
        row.put("message", message);
        row.put("@timestamp", time);
        row.put(QueryResult.RAW_REF_KEY, "group-buy-market-log-2026.03.27#doc1");
        return row;
    }

    private Map<String, Object> orderRow(String time, String orderNo, String status, String failReason, String amount) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("order_no", orderNo);
        row.put("status", status);
        row.put("fail_reason", failReason);
        row.put("amount", amount);
        row.put("create_time", time);
        row.put(QueryResult.RAW_REF_KEY, "order_no=" + orderNo);
        return row;
    }

    private void inject(Object target, String field, Object value) {
        try {
            Field f = target.getClass().getDeclaredField(field);
            f.setAccessible(true);
            f.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("注入测试桩失败：" + field, e);
        }
    }

    /**
     * 查询门面桩：让测试脱离 ES/MySQL，并能模拟"某一侧查询失败"
     */
    private static final class StubQueryService extends DataSourceQueryService {

        private QueryResult logResult;

        private QueryResult orderResult;

        private RuntimeException queryThrows;

        private int searchCalls;

        private int queryCalls;

        /** 记录最后一次实体查询的路由目标，用于断言"对齐了哪一侧" */
        private String lastQueryDsId;

        private String lastQueryEntityCode;

        @Override
        public QueryResult search(String dsId, LogSearchQuery query) {
            searchCalls++;
            return logResult;
        }

        @Override
        public QueryResult query(String dsId, EntityQuery query) {
            queryCalls++;
            lastQueryDsId = dsId;
            lastQueryEntityCode = query == null ? null : query.getEntityCode();
            if (queryThrows != null) {
                throw queryThrows;
            }
            return orderResult;
        }

    }

}
