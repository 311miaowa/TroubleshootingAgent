package cn.bugstack.ai.test.m8;

import cn.bugstack.ai.domain.agent.adapter.port.datasource.DataSourceCapability;
import cn.bugstack.ai.domain.agent.adapter.port.datasource.IDataSourceProvider;
import cn.bugstack.ai.domain.agent.adapter.port.datasource.IDataSourceRegistry;
import cn.bugstack.ai.domain.agent.adapter.port.datasource.ILogSearchProvider;
import cn.bugstack.ai.domain.agent.model.valobj.datasource.EntityMeta;
import cn.bugstack.ai.domain.agent.model.valobj.datasource.FaultWindow;
import cn.bugstack.ai.domain.agent.model.valobj.datasource.LogSearchQuery;
import cn.bugstack.ai.domain.agent.model.valobj.datasource.QueryResult;
import cn.bugstack.ai.domain.agent.service.datasource.DataSourceQueryService;
import cn.bugstack.ai.domain.agent.service.datasource.FaultWindowComparisonService;
import org.junit.Assert;
import org.junit.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * M8 故障期 vs 基线对比单测（纯单测：假注册表 + 假查询服务，不启动 Spring、不连数据源）
 * <p>
 * 钉住的是**输出契约**：phase 标注的桶、每桶/每组速率、"合计"行、降级告警（镜像构造/取数失败/不做因果）。
 *
 * @author miaowazhongzi
 */
public class FaultWindowComparisonTest {

    private static final Instant NOW = Instant.parse("2026-03-27T12:00:00Z");
    private static final String START = "2026-03-27T10:00:00Z";
    private static final String END = "2026-03-27T11:00:00Z";
    private static final String PREV_END = "2026-03-27T08:00:00Z";

    /** 能产出 phase 桶 + "合计"行，且速率按各自窗口长度计算（**桶标签按 Asia/Shanghai 展示**：10:02Z → 18:00） */
    @Test
    public void compare_emitsPhaseBucketsAndTotals() throws Exception {
        FakeQueryService query = new FakeQueryService();
        // 故障期（10:00~11:00Z）：10:02Z、10:07Z 分属 18:00 / 18:05（+08:00）；10:33Z 落 18:30
        query.put(Instant.parse(START), rows("2026-03-27T10:02:00Z", "2026-03-27T10:07:00Z", "2026-03-27T10:33:00Z"));
        // 前基线（08:10~09:50Z）：1 条 → 16:20
        query.put(Instant.parse("2026-03-27T08:10:00Z"), rows("2026-03-27T08:20:00Z"));

        QueryResult result = serviceWith(query).compare("限流", window(), NOW, 100);

        Assert.assertEquals(List.of("phase", "bucketStart", "count", "countPerMinute"), result.getColumns());
        Assert.assertEquals(FaultWindowComparisonService.SUMMARY_ENTITY_CODE, result.getEntityCode());
        Assert.assertEquals("总命中 = 故障期 3 + 基线 1", 4L, result.getTotal());

        // 故障期三个桶各 1 条（0.2/分钟）
        Map<String, Object> firstBucket = find(result, "fault", "2026-03-27 18:00:00");
        Assert.assertEquals(1, firstBucket.get("count"));
        Assert.assertEquals(0.2d, (double) firstBucket.get("countPerMinute"), 0.001d);
        find(result, "fault", "2026-03-27 18:05:00");
        find(result, "fault", "2026-03-27 18:30:00");

        // 故障期"合计"：3 条 / 60 分钟 = 0.05/分钟
        Map<String, Object> faultTotal = find(result, "fault", FaultWindowComparisonService.TOTAL_LABEL);
        Assert.assertEquals(3, faultTotal.get("count"));
        Assert.assertEquals(0.05d, (double) faultTotal.get("countPerMinute"), 0.001d);

        // 前基线"合计"：1 条 / 100 分钟 = 0.01/分钟
        Map<String, Object> baselineTotal = find(result, "baseline_before", FaultWindowComparisonService.TOTAL_LABEL);
        Assert.assertEquals(1, baselineTotal.get("count"));
        Assert.assertEquals(0.01d, (double) baselineTotal.get("countPerMinute"), 0.001d);
        find(result, "baseline_before", "2026-03-27 16:20:00");
    }

    /** 没给故障窗：返回空结果 + 明确告警（不猜窗口，也不抛异常） */
    @Test
    public void compare_withoutWindow_warns() throws Exception {
        QueryResult result = serviceWith(new FakeQueryService()).compare("限流", null, NOW, 100);

        Assert.assertTrue(result.getRows().isEmpty());
        Assert.assertTrue("应告警未提供故障窗：" + result.getWarnings(),
                result.getWarnings().stream().anyMatch(w -> w.contains("未提供故障窗")));
    }

    /** 没给上次故障结束：前基线镜像构造，且 warnings 必须写明（M8 §4 硬要求） */
    @Test
    public void compare_mirrorBaseline_isWarned() throws Exception {
        FaultWindow mirror = FaultWindow.parse(START, END, null, null, null);

        QueryResult result = serviceWith(new FakeQueryService()).compare("限流", mirror, NOW, 100);

        Assert.assertTrue("必须说明镜像构造：" + result.getWarnings(),
                result.getWarnings().stream().anyMatch(w -> w.contains("镜像构造")));
        Assert.assertTrue("必须说明没有后基线：" + result.getWarnings(),
                result.getWarnings().stream().anyMatch(w -> w.contains("未构造后基线")));
    }

    /** 某一段取数失败：该段不产出，但其它段照常，并告警 */
    @Test
    public void compare_onePhaseFails_othersSurvive() throws Exception {
        FakeQueryService query = new FakeQueryService();
        query.put(Instant.parse(START), rows("2026-03-27T10:02:00Z"));
        query.failOn(Instant.parse("2026-03-27T08:10:00Z"));

        QueryResult result = serviceWith(query).compare("限流", window(), NOW, 100);

        Assert.assertTrue("故障期仍应产出", hasPhase(result, "fault"));
        Assert.assertFalse("基线不应产出", hasPhase(result, "baseline_before"));
        Assert.assertTrue("应告警取数失败：" + result.getWarnings(),
                result.getWarnings().stream().anyMatch(w -> w.contains("取数失败")));
    }

    /** 纪律：结果里必须写明"只做机械对比、不代表因果" */
    @Test
    public void compare_warnsNoCausality() throws Exception {
        QueryResult result = serviceWith(new FakeQueryService()).compare("限流", window(), NOW, 100);

        Assert.assertTrue("必须写明不做因果判断：" + result.getWarnings(),
                result.getWarnings().stream().anyMatch(w -> w.contains("不代表因果")));
        Assert.assertTrue("应带结果级 rawRef（证据回指）", result.getRawRef().startsWith("faultwindow://"));
    }

    // ------------------------------------------------------------------ 夹具

    private FaultWindow window() {
        return FaultWindow.parse(START, END, PREV_END, null, "下单失败");
    }

    private FaultWindowComparisonService serviceWith(DataSourceQueryService queryService) throws Exception {
        FaultWindowComparisonService service = new FaultWindowComparisonService();
        inject(service, "dataSourceRegistry", registry());
        inject(service, "dataSourceQueryService", queryService);
        return service;
    }

    private Map<String, Object> find(QueryResult result, String phase, String bucketStart) {
        Optional<Map<String, Object>> hit = result.getRows().stream()
                .filter(r -> phase.equals(r.get("phase")) && bucketStart.equals(r.get("bucketStart")))
                .findFirst();
        Assert.assertTrue("未找到行 phase=" + phase + " bucketStart=" + bucketStart + "，实际=" + result.getRows(),
                hit.isPresent());
        return hit.get();
    }

    private boolean hasPhase(QueryResult result, String phase) {
        return result.getRows().stream().anyMatch(r -> phase.equals(r.get("phase")));
    }

    private List<Map<String, Object>> rows(String... timestamps) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (String timestamp : timestamps) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("@timestamp", timestamp);
            row.put("level", "ERROR");
            row.put("message", "限流 " + timestamp);
            rows.add(row);
        }
        return rows;
    }

    private IDataSourceRegistry registry() {
        Set<DataSourceCapability> capabilities = Set.of(DataSourceCapability.LOG_SEARCH);
        List<EntityMeta> entities = List.of(EntityMeta.builder()
                .dsId("ds-es").entityCode("logs").entityName("日志").capability("log_search").build());
        IDataSourceProvider provider = (IDataSourceProvider) Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[]{IDataSourceProvider.class, ILogSearchProvider.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "dsId" -> "ds-es";
                    case "capabilities" -> capabilities;
                    case "listEntities" -> entities;
                    default -> null;
                });
        return (IDataSourceRegistry) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{IDataSourceRegistry.class},
                (proxy, method, args) -> "listByTenant".equals(method.getName()) ? List.of(provider) : null);
    }

    private void inject(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    /**
     * 假查询服务：按"时间窗起点"回放预置行；可指定某段抛异常
     */
    private static final class FakeQueryService extends DataSourceQueryService {

        private final Map<Instant, List<Map<String, Object>>> rowsByStart = new HashMap<>();

        private final Set<Instant> failing = new HashSet<>();

        void put(Instant start, List<Map<String, Object>> rows) {
            rowsByStart.put(start, rows);
        }

        void failOn(Instant start) {
            failing.add(start);
        }

        @Override
        public QueryResult search(String dsId, LogSearchQuery query) {
            Instant key = query.getTimeRange() == null ? null : query.getTimeRange().getStart();
            if (failing.contains(key)) {
                throw new IllegalStateException("模拟取数失败");
            }
            List<Map<String, Object>> rows = rowsByStart.getOrDefault(key, List.of());
            return QueryResult.builder().dsId(dsId).entityCode("logs")
                    .rows(new ArrayList<>(rows)).total(rows.size()).build();
        }
    }

}
