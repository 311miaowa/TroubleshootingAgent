package cn.bugstack.ai.test.m7;

import cn.bugstack.ai.domain.agent.model.valobj.datasource.QueryResult;
import cn.bugstack.ai.domain.agent.service.datasource.AnomalySummarizationService;
import cn.bugstack.ai.domain.agent.service.datasource.LogPatternNormalizer;
import com.alibaba.fastjson.JSON;
import org.junit.Assert;
import org.junit.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * M7a 异常归纳层单测（纯单测：不启动 Spring、不连数据源）
 * <p>
 * 钉住的是**统计口径**，不是实现细节：归一规则、分组与占比、时间桶、中位数+IQR 离群、
 * 小样本不标异常、rawRef 双通道、以及那几条必须出现的告警。
 * <p>
 * 注意：本模块 surefire 用的是 <b>JUnit 4</b> provider（离线仓库无 platform provider），
 * 因此这里必须是 {@code org.junit.Test} + {@code public void}（JUnit 5 的用例不会被发现，且是静默忽略）。
 *
 * @author miaowazhongzi
 */
public class AnomalySummarizationTest {

    private final AnomalySummarizationService service = new AnomalySummarizationService();

    // ------------------------------------------------------------------ 归一

    /** 归一：实例值（时间戳/UUID/IP/数字）不同但内容相同的行，归为同一模板 */
    @Test
    public void normalize_ignoresInstanceValues() {
        String a = "2026-03-27 10:00:01 ERROR OrderService - 下单失败 orderId=1001, cost=120ms, ip=10.1.2.3";
        String b = "2026-03-27 10:07:45 ERROR OrderService - 下单失败 orderId=987654, cost=9ms, ip=192.168.0.77";

        String pa = LogPatternNormalizer.normalize(a);
        String pb = LogPatternNormalizer.normalize(b);

        Assert.assertEquals("同一类内容应归为同一模板", pa, pb);
        Assert.assertTrue("数字应被归一：" + pa, pa.contains("<num>"));
        Assert.assertTrue("IP 应被归一：" + pa, pa.contains("<ip>"));
        Assert.assertFalse("时间戳应被归一：" + pa, pa.contains("2026-03-27"));
    }

    /** 归一：不同内容不会被并成一类（避免把不同故障归为同一现象） */
    @Test
    public void normalize_keepsDifferentMessagesApart() {
        Assert.assertNotEquals(
                LogPatternNormalizer.normalize("连接超时 timeout=3000ms"),
                LogPatternNormalizer.normalize("库存不足 stock=0"));
    }

    /** 归一：超长消息被截断到 200 字符；空白输入归为 UNKNOWN */
    @Test
    public void normalize_truncatesAndFallsBack() {
        String longText = "x".repeat(500);
        Assert.assertEquals(LogPatternNormalizer.MAX_PATTERN_LENGTH,
                LogPatternNormalizer.normalize(longText).length());
        Assert.assertEquals(LogPatternNormalizer.UNKNOWN, LogPatternNormalizer.normalize("   "));
        Assert.assertEquals(LogPatternNormalizer.UNKNOWN, LogPatternNormalizer.normalize(null));
    }

    // ------------------------------------------------------------------ 分组与占比

    /** 归纳：按 模板+level 分组，share 为窗口内占比，firstSeen/lastSeen 正确 */
    @Test
    public void summarize_groupsAndComputesShare() {
        List<Map<String, Object>> rows = new ArrayList<>();
        rows.add(row("2026-03-27 10:00:00", "ERROR", "下单失败 orderId=1"));
        rows.add(row("2026-03-27 10:06:00", "ERROR", "下单失败 orderId=2"));
        rows.add(row("2026-03-27 10:11:00", "ERROR", "下单失败 orderId=3"));
        rows.add(row("2026-03-27 10:12:00", "WARN", "限流触发 count=5"));

        QueryResult result = service.summarizeRows("ds-es", "logs", rows, 20);

        Assert.assertEquals("应归为 2 个现象组", 2, result.getRows().size());
        Map<String, Object> first = result.getRows().get(0);
        Assert.assertEquals("计数最多的组应排在第一", 3, first.get("count"));
        // 占比 = 3/4（JUnit4 没有带消息的 double+delta 重载，消息放注释里）
        Assert.assertEquals(0.75d, (double) first.get("share"), 0.0001d);
        Assert.assertEquals("2026-03-27 10:00:00", first.get("firstSeen"));
        Assert.assertEquals("2026-03-27 10:11:00", first.get("lastSeen"));
        Assert.assertEquals(AnomalySummarizationService.SUMMARY_ENTITY_CODE, result.getEntityCode());
        Assert.assertNotNull("结果级 rawRef 必须给出覆盖范围", result.getRawRef());
        Assert.assertTrue("rawRef 应描述覆盖范围：" + result.getRawRef(),
                result.getRawRef().startsWith("summary://ds-es/logs@"));
    }

    /** 归纳：level 不同的同类内容算两个现象（ERROR 与 WARN 不能混） */
    @Test
    public void summarize_splitsByLevel() {
        List<Map<String, Object>> rows = new ArrayList<>();
        rows.add(row("2026-03-27 10:00:00", "ERROR", "下单失败 orderId=1"));
        rows.add(row("2026-03-27 10:01:00", "WARN", "下单失败 orderId=2"));

        QueryResult result = service.summarizeRows("ds-es", "logs", rows, 20);

        Assert.assertEquals(2, result.getRows().size());
        Assert.assertEquals(List.of("ERROR", "WARN"), result.getRows().stream()
                .map(r -> String.valueOf(r.get("level"))).sorted().collect(Collectors.toList()));
    }

    // ------------------------------------------------------------------ 离群判定

    /** 离群：有效桶 ≥5 且峰值桶 > 中位数+3×IQR 时标 outlier=true */
    @Test
    public void summarize_marksOutlierBucket() {
        List<Map<String, Object>> rows = new ArrayList<>();
        // 4 个平静桶各 1 条 + 1 个爆量桶 40 条（同一模板，数字不同 → 归为一类）
        rows.add(row("2026-03-27 10:00:10", "ERROR", "连接超时 timeout=3000ms"));
        rows.add(row("2026-03-27 10:05:10", "ERROR", "连接超时 timeout=3000ms"));
        rows.add(row("2026-03-27 10:10:10", "ERROR", "连接超时 timeout=3000ms"));
        rows.add(row("2026-03-27 10:15:10", "ERROR", "连接超时 timeout=3000ms"));
        for (int i = 0; i < 40; i++) {
            rows.add(row("2026-03-27 10:20:" + String.format("%02d", i % 60), "ERROR",
                    "连接超时 timeout=" + (3000 + i) + "ms"));
        }

        QueryResult result = service.summarizeRows("ds-es", "logs", rows, 20);

        Map<String, Object> group = result.getRows().get(0);
        Assert.assertEquals(44, group.get("count"));
        Assert.assertEquals(40, group.get("peakCount"));
        Assert.assertEquals("2026-03-27 10:20:00", group.get("peakBucket"));
        Assert.assertEquals("峰值桶应被判为离群", Boolean.TRUE, group.get("outlier"));
    }

    /** 离群：有效桶不足 5 个时不做判定（IQR 没有统计意义） */
    @Test
    public void summarize_noOutlierWhenBucketsInsufficient() {
        List<Map<String, Object>> rows = new ArrayList<>();
        rows.add(row("2026-03-27 10:00:10", "ERROR", "连接超时 timeout=3000ms"));
        for (int i = 0; i < 40; i++) {
            rows.add(row("2026-03-27 10:20:" + String.format("%02d", i % 60), "ERROR",
                    "连接超时 timeout=" + i + "ms"));
        }

        QueryResult result = service.summarizeRows("ds-es", "logs", rows, 20);

        Assert.assertEquals("桶数不足时不得标离群", Boolean.FALSE, result.getRows().get(0).get("outlier"));
    }

    /** 小样本：命中 < 3 条的现象不标异常，并给出样本量不足告警 */
    @Test
    public void summarize_smallSampleNotMarkedAbnormal() {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int bucket = 0; bucket < 6; bucket++) {
            // 用互不相同的两字母标记：若只改数字会被归一规则并成同一个现象（这是预期行为）
            rows.add(row("2026-03-27 10:" + String.format("%02d", bucket * 5) + ":10", "ERROR",
                    "偶发失败 " + twoLetters(bucket)));
        }

        QueryResult result = service.summarizeRows("ds-es", "logs", rows, 20);

        Assert.assertEquals(6, result.getRows().size());
        Assert.assertTrue("1 条日志不构成异常现象",
                result.getRows().stream().allMatch(r -> Boolean.FALSE.equals(r.get("outlier"))));
        Assert.assertTrue("必须告警样本量不足：" + result.getWarnings(),
                result.getWarnings().stream().anyMatch(w -> w.contains("样本量不足")));
    }

    // ------------------------------------------------------------------ 告警与截断

    /** 告警：永远带上「未提供正常期基线」——防止把窗口内计数读成「比平时高」 */
    @Test
    public void summarize_alwaysWarnsNoBaseline() {
        List<Map<String, Object>> rows = new ArrayList<>();
        rows.add(row("2026-03-27 10:00:00", "ERROR", "下单失败 orderId=1"));

        QueryResult result = service.summarizeRows("ds-es", "logs", rows, 20);

        Assert.assertTrue("无基线告警必须出现：" + result.getWarnings(),
                result.getWarnings().stream().anyMatch(w -> w.contains("未提供正常期基线")));
    }

    /** 脏数据：时间字段解析不出来的行被跳过并计数告警，不猜默认时间 */
    @Test
    public void summarize_skipsUnparsableTime() {
        List<Map<String, Object>> rows = new ArrayList<>();
        rows.add(row("2026-03-27 10:00:00", "ERROR", "下单失败 orderId=1"));
        rows.add(row("not-a-time", "ERROR", "下单失败 orderId=2"));
        rows.add(row(null, "ERROR", "下单失败 orderId=3"));

        QueryResult result = service.summarizeRows("ds-es", "logs", rows, 20);

        Assert.assertEquals(1, result.getRows().size());
        Assert.assertEquals(1, result.getRows().get(0).get("count"));
        Assert.assertTrue("应告警被跳过的行数：" + result.getWarnings(),
                result.getWarnings().stream().anyMatch(w -> w.contains("时间解析失败")));
    }

    /** 截断：现象组超过 limit 时只返回计数最高的若干组，并告警 */
    @Test
    public void summarize_truncatesGroups() {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            // 30 个互不相同的模板（只改数字会被归并成 1 个现象）
            rows.add(row("2026-03-27 10:00:00", "ERROR", "故障类型 " + twoLetters(i) + " code=" + i));
        }

        QueryResult result = service.summarizeRows("ds-es", "logs", rows, 5);

        Assert.assertEquals(5, result.getRows().size());
        Assert.assertTrue(result.isTruncated());
        Assert.assertTrue("应告警被截断：" + result.getWarnings(),
                result.getWarnings().stream().anyMatch(w -> w.contains("只返回计数最高的")));
    }

    /** 空输入：0 条命中时返回空结果与告警，不抛异常 */
    @Test
    public void summarize_emptyRows() {
        QueryResult result = service.summarizeRows("ds-es", "logs", List.of(), 20);

        Assert.assertTrue(result.getRows().isEmpty());
        Assert.assertTrue(result.getWarnings().stream().anyMatch(w -> w.contains("0 条命中")));
    }

    // ------------------------------------------------------------------ rawRef 双通道

    /** rawRef 双通道：事件版保留结果级 rawRef 与行级 _rawRef，模型版必须剥离 */
    @Test
    public void modelJson_stripsRawRefs() {
        List<Map<String, Object>> rows = new ArrayList<>();
        rows.add(row("2026-03-27 10:00:00", "ERROR", "下单失败 orderId=1",
                "es://group-buy-market-log-2026.03.27/abc123"));
        rows.add(row("2026-03-27 10:01:00", "ERROR", "下单失败 orderId=2",
                "es://group-buy-market-log-2026.03.27/def456"));

        QueryResult result = service.summarizeRows("ds-es", "logs", rows, 20);

        String eventJson = JSON.toJSONString(result);
        Assert.assertTrue("事件版应含行级 _rawRef", eventJson.contains(QueryResult.RAW_REF_KEY));
        Assert.assertTrue("事件版应含结果级 rawRef", eventJson.contains("summary://"));
        Assert.assertTrue("事件版应含具体原文引用（证据面板回指用）", eventJson.contains("abc123"));

        String modelJson = result.toModelJson();
        Assert.assertFalse("模型版必须剥离行级 _rawRef", modelJson.contains(QueryResult.RAW_REF_KEY));
        Assert.assertFalse("模型版必须剥离结果级 rawRef", modelJson.contains("summary://"));
        Assert.assertFalse("模型版不得出现原文引用", modelJson.contains("abc123"));
        Assert.assertTrue("模型版仍需保留统计结论本身", modelJson.contains("下单失败"));
    }

    // ------------------------------------------------------------------ 工具函数

    /**
     * 生成互不相同的两字母标记（aa/ab/…）：用来构造"模板不同"的测试数据——
     * 只改数字会被 {@link LogPatternNormalizer} 正确地并成同一个现象。
     */
    private String twoLetters(int index) {
        return "" + (char) ('a' + index / 26) + (char) ('a' + index % 26);
    }

    private Map<String, Object> row(String timestamp, String level, String message) {
        return row(timestamp, level, message, null);
    }

    private Map<String, Object> row(String timestamp, String level, String message, String rawRef) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("@timestamp", timestamp);
        row.put("level", level);
        row.put("message", message);
        if (rawRef != null) {
            row.put(QueryResult.RAW_REF_KEY, rawRef);
        }
        return row;
    }

}
