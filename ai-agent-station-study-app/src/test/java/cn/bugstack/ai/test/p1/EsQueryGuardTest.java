package cn.bugstack.ai.test.p1;

import cn.bugstack.ai.infrastructure.gateway.datasource.EsQueryGuard;
import org.junit.Assert;
import org.junit.Test;

/**
 * P1 单测：ES 查询守卫（{@code SqlTemplateRenderer} 在 ES 侧的<b>对等物</b>）。
 * <p>
 * 守的是<b>只读边界</b>，不是"能不能跑"：
 * <ul>
 *     <li>索引/索引模式标识符必须匹配白名单正则（否则它能改变请求路径）；</li>
 *     <li>渲染后的 {@code _search} DSL 顶层键必须在白名单内，任意层级不得出现被禁键；</li>
 *     <li>{@code size}/{@code from} 必须是非负整数且不超上限；</li>
 *     <li><b>真实配置必须仍然通过</b>（{@code group-buy-market-log-*} 与线上模板）——守卫不能把正常查询拦死。</li>
 * </ul>
 * <p>
 * 纯单测、无 Spring 上下文，默认 {@code mvn -o -B test} 就会执行。
 *
 * @author miaowazhongzi
 */
public class EsQueryGuardTest {

    /** 与本机真实配置一致（docs/dev-ops/mysql/sql/upgrade-m1-1-align-es-log-entity.sql） */
    private static final String REAL_PHYSICAL_NAME = "group-buy-market-log-*";

    /** 与本机真实 query_template 一致（占位符已替换成具体值后的形态） */
    private static final String REAL_BODY =
            "{\"query\":{\"bool\":{\"must\":["
                    + "{\"range\":{\"@timestamp\":{\"gte\":\"2026-03-27T00:00:00Z\",\"lte\":\"2026-03-28T00:00:00Z\"}}},"
                    + "{\"match\":{\"message\":\"限流\"}}]}},"
                    + "\"sort\":[{\"@timestamp\":{\"order\":\"desc\"}}],\"size\":20}";

    // ------------------------------------------------------------------ 正常路径（守卫不能拦死真实配置）

    @Test
    public void realPhysicalName_isAccepted() {
        Assert.assertNull("真实索引模式必须通过：" + REAL_PHYSICAL_NAME,
                EsQueryGuard.validatePhysicalName(REAL_PHYSICAL_NAME));
    }

    @Test
    public void realQueryBody_isAccepted() {
        Assert.assertNull("真实查询模板渲染结果必须通过：" + EsQueryGuard.validateSearchBody(REAL_BODY),
                EsQueryGuard.validateSearchBody(REAL_BODY));
    }

    /**
     * 钉住<b>未替换占位符</b>的线上模板原文（取自库表实测，见类注释）——连带上游一起固化：
     * 只要有人把模板改成含 {@code script} 或非白名单顶层键的形态，本用例就会红。
     * <p>
     * 同时用应用真实的替换顺序渲染一遍，确保渲染产物也通过守卫。
     */
    @Test
    public void liveTemplateFromDatabase_passesGuard() {
        String liveTemplate = "{\"query\":{\"bool\":{\"must\":["
                + "{\"range\":{\"@timestamp\":{\"gte\":\"${start}\",\"lte\":\"${end}\"}}},"
                + "{\"match\":{\"message\":\"${keyword}\"}}]}},"
                + "\"sort\":[{\"@timestamp\":{\"order\":\"desc\"}}],\"size\":${size}}";

        String rendered = liveTemplate
                .replace("${keyword}", "限流")
                .replace("${start}", "2026-03-27T00:00:00Z")
                .replace("${end}", "2026-03-28T00:00:00Z")
                .replace("${size}", "20");

        String error = EsQueryGuard.validateSearchBody(rendered);
        Assert.assertNull("线上模板渲染结果必须通过守卫，否则正常查询会被拦死。原因=" + error, error);
    }

    @Test
    public void commonIndexNames_areAccepted() {
        for (String name : new String[]{"logs", "group-buy-market-log-2026.03.27",
                "springboot-logstash-2026.03.27", "a.b_c-d", "logs-*", "*-2026.03.*"}) {
            Assert.assertNull("应通过：" + name, EsQueryGuard.validatePhysicalName(name));
        }
    }

    // ------------------------------------------------------------------ 标识符：必须拒绝

    @Test
    public void pathTraversalAndSeparators_areRejected() {
        for (String name : new String[]{
                "logs/../../_bulk",      // 路径穿越
                "logs/_doc/1",           // 改写路径
                "../logs",               // 相对路径
                "logs\\_bulk",           // 反斜杠
                "http://evil/",          // 绝对 URL
                "logs?pretty",           // 查询串
                "logs,other",            // ES 多目标语法
                "logs#frag",
                "logs%2F_bulk",          // 编码后的 /
                "logs 1",                // 空白
                "logs\n_bulk",           // 控制字符
                "LOGS",                  // 大写（ES 索引名不允许）
                "*",                     // 裸通配 = 放开全部索引（正则允许 *，由显式检查拦下）
                "_all",                  // ES 保留
                ".kibana",               // 点开头
                ""}) {
            Assert.assertNotNull("必须拒绝：" + repr(name), EsQueryGuard.validatePhysicalName(name));
        }
        Assert.assertNotNull("null 必须拒绝", EsQueryGuard.validatePhysicalName(null));
    }

    /**
     * 裸通配必须被拒绝——但断言它<b>不是因为正则不匹配</b>才被拒。
     * <p>
     * 否则该用例会"因为错误的理由通过"（{@code *} 本来就在字符类里，
     * 一旦有人删掉显式检查，正则仍会放行）。这里直接钉住原因文本。
     */
    @Test
    public void bareWildcard_isRejectedForTheRightReason() {
        String reason = EsQueryGuard.validatePhysicalName("*");
        Assert.assertNotNull("裸通配必须拒绝", reason);
        Assert.assertTrue("拒绝原因应指向裸通配，而非正则不匹配。实际=" + reason,
                reason.contains("裸通配"));
    }

    @Test
    public void overlongPhysicalName_isRejected() {
        String tooLong = "a".repeat(EsQueryGuard.MAX_PHYSICAL_NAME_LENGTH + 1);
        Assert.assertNotNull("超长必须拒绝", EsQueryGuard.validatePhysicalName(tooLong));
    }

    // ------------------------------------------------------------------ DSL：必须拒绝

    @Test
    public void scriptConstructs_areRejectedAtAnyDepth() {
        // 顶层
        Assert.assertNotNull("script_fields 在顶层必须拒绝",
                EsQueryGuard.validateSearchBody("{\"script_fields\":{\"x\":{\"script\":\"1\"}}}"));
        // 深层嵌套
        Assert.assertNotNull("script 在深层必须拒绝",
                EsQueryGuard.validateSearchBody(
                        "{\"query\":{\"bool\":{\"must\":[{\"script\":{\"script\":\"doc['a'].value > 1\"}}]}}}"));
        Assert.assertNotNull("runtime_mappings 必须拒绝",
                EsQueryGuard.validateSearchBody(
                        "{\"runtime_mappings\":{\"f\":{\"type\":\"keyword\",\"script\":{\"source\":\"emit(1)\"}}}}"));
    }

    @Test
    public void writeSemanticsKeys_areRejected() {
        for (String body : new String[]{
                "{\"doc\":{\"a\":1}}",
                "{\"upsert\":{\"a\":1}}",
                "{\"doc_as_upsert\":true}",
                "{\"update\":{\"a\":1}}",
                "{\"pipeline\":\"my-pipeline\",\"query\":{\"match_all\":{}}}",
                "{\"query\":{\"match_all\":{}},\"script\":{\"source\":\"1\"}}"}) {
            Assert.assertNotNull("必须拒绝：" + body, EsQueryGuard.validateSearchBody(body));
        }
    }

    @Test
    public void unknownTopLevelKey_isRejectedByAllowlist() {
        Assert.assertNotNull("未授权顶层键必须拒绝（白名单而非黑名单）",
                EsQueryGuard.validateSearchBody("{\"query\":{\"match_all\":{}},\"some_future_construct\":{}}"));
        // 反证：白名单里的只读键正常放行
        Assert.assertNull("只读聚合应放行",
                EsQueryGuard.validateSearchBody("{\"size\":0,\"aggs\":{\"c\":{\"terms\":{\"field\":\"level\"}}}}"));
    }

    @Test
    public void invalidJson_isRejected() {
        Assert.assertNotNull("非法 JSON 必须拒绝", EsQueryGuard.validateSearchBody("{not json"));
        Assert.assertNotNull("非对象必须拒绝", EsQueryGuard.validateSearchBody("[1,2,3]"));
        Assert.assertNotNull("空串必须拒绝", EsQueryGuard.validateSearchBody(""));
        Assert.assertNotNull("null 必须拒绝", EsQueryGuard.validateSearchBody(null));
    }

    // ------------------------------------------------------------------ size / from 边界

    @Test
    public void sizeAndFromBounds_areEnforced() {
        Assert.assertNull("size=0 应放行", EsQueryGuard.validateSearchBody("{\"size\":0}"));
        Assert.assertNull("size=上限 应放行",
                EsQueryGuard.validateSearchBody("{\"size\":" + EsQueryGuard.MAX_SIZE + "}"));
        Assert.assertNotNull("size 超上限 必须拒绝",
                EsQueryGuard.validateSearchBody("{\"size\":" + (EsQueryGuard.MAX_SIZE + 1) + "}"));
        Assert.assertNotNull("size 为负 必须拒绝", EsQueryGuard.validateSearchBody("{\"size\":-1}"));
        Assert.assertNotNull("size 非整数 必须拒绝", EsQueryGuard.validateSearchBody("{\"size\":\"很多\"}"));
        Assert.assertNotNull("from 为负 必须拒绝", EsQueryGuard.validateSearchBody("{\"from\":-5}"));
        Assert.assertNotNull("from 超上限 必须拒绝",
                EsQueryGuard.validateSearchBody("{\"from\":" + (EsQueryGuard.MAX_SIZE + 1) + "}"));
    }

    // ------------------------------------------------------------------ 告警文本本身的安全

    @Test
    public void rejectionReason_doesNotLeakControlCharacters() {
        String reason = EsQueryGuard.validatePhysicalName("logs\n_bulk\u0007");
        Assert.assertNotNull(reason);
        Assert.assertFalse("原因文本不得含换行/控制字符", reason.matches("(?s).*[\\p{Cntrl}].*"));
    }

    private String repr(String s) {
        return s == null ? "null" : "[" + s.replace("\n", "\\n") + "]";
    }

}
