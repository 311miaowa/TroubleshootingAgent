package cn.bugstack.ai.test.p1;

import cn.bugstack.ai.domain.agent.model.valobj.datasource.EntityMeta;
import cn.bugstack.ai.domain.agent.model.valobj.datasource.FieldFilter;
import cn.bugstack.ai.infrastructure.gateway.datasource.SqlTemplateRenderer;
import org.junit.Assert;
import org.junit.Test;

import java.util.List;
import java.util.Map;

/**
 * P1-2 单测：SQL 模板渲染与「通用过滤」。
 * <p>
 * 这里守的是<b>安全边界</b>，不是"能不能跑"：
 * <ul>
 *     <li>过滤字段必须来自字段字典且 {@code is_filterable=1}，且必须匹配标识符正则；</li>
 *     <li>过滤<b>值</b>永远只出现在参数里，绝不拼进 SQL 字符串；</li>
 *     <li>模板没启用 {@code ${filters}} 时，过滤条件必须"被忽略并告警"，而不是静默丢弃；</li>
 *     <li>占位符按出现顺序对位参数（模板挪动 {@code ${filters}} 的位置也不会错位）。</li>
 * </ul>
 *
 * @author miaowazhongzi
 */
public class SqlTemplateRendererTest {

    private static final String ORDER_TEMPLATE = "SELECT order_no FROM ai_order WHERE create_time >= ${start} "
            + "AND create_time <= ${end} AND (order_no = ${orderNo} OR ${orderNo} IS NULL) "
            + "ORDER BY create_time DESC LIMIT ${limit}";

    private static final String PAYMENT_TEMPLATE = "SELECT order_no FROM payment_transaction WHERE create_time >= ${start} "
            + "AND create_time <= ${end} ${filters} ORDER BY create_time DESC LIMIT ${limit}";

    private static final List<EntityMeta.FieldMeta> PAYMENT_FIELDS = List.of(
            field("order_no", "varchar", true),
            field("pay_channel", "varchar", true),
            field("status", "varchar", true),
            field("amount", "numeric", true),
            field("duration_ms", "integer", false),
            field("create_time", "timestamp", true));

    // ------------------------------------------------------------------ 标量占位符

    @Test
    public void scalarPlaceholders_arePositional_andOrderTemplateKeepsOldBehavior() {
        SqlTemplateRenderer.Rendered rendered = SqlTemplateRenderer.render(
                ORDER_TEMPLATE,
                SqlTemplateRenderer.values("TS1", "TS2", "NO1", 50),
                null,
                null,
                SqlTemplateRenderer.Dialect.MYSQL);

        // 与改造前硬编码的 args = {start, end, orderNo, orderNo, limit} 完全一致
        Assert.assertEquals(List.of("TS1", "TS2", "NO1", "NO1", 50), rendered.getArgs());
        Assert.assertEquals(5, rendered.getSql().chars().filter(c -> c == '?').count());
        Assert.assertTrue(rendered.getWarnings().isEmpty());
    }

    @Test
    public void filtersPlaceholder_keepsArgumentOrderAroundIt() {
        SqlTemplateRenderer.Rendered rendered = SqlTemplateRenderer.render(
                PAYMENT_TEMPLATE,
                SqlTemplateRenderer.values("TS1", "TS2", null, 50),
                List.of(FieldFilter.eq("status", "TIMEOUT"), FieldFilter.eq("pay_channel", "WECHAT")),
                PAYMENT_FIELDS,
                SqlTemplateRenderer.Dialect.POSTGRES);

        Assert.assertEquals(List.of("TS1", "TS2", "TIMEOUT", "WECHAT", 50), rendered.getArgs());
        Assert.assertTrue(rendered.getSql().contains("AND status = ?"));
        Assert.assertTrue(rendered.getSql().contains("AND pay_channel = ?"));
        Assert.assertTrue("过滤片段必须落在 时间窗 与 ORDER BY 之间",
                rendered.getSql().indexOf("AND status") > rendered.getSql().indexOf("create_time <= ?"));
        Assert.assertTrue(rendered.getSql().indexOf("AND status") < rendered.getSql().indexOf("ORDER BY"));
    }

    // ------------------------------------------------------------------ 白名单 / 注入防护

    @Test
    public void filterValue_neverAppearsInSql() {
        String attack = "TIMEOUT' OR '1'='1";
        SqlTemplateRenderer.Rendered rendered = SqlTemplateRenderer.render(
                PAYMENT_TEMPLATE,
                SqlTemplateRenderer.values("TS1", "TS2", null, 50),
                List.of(FieldFilter.eq("status", attack)),
                PAYMENT_FIELDS,
                SqlTemplateRenderer.Dialect.POSTGRES);

        Assert.assertFalse("值绝不能出现在 SQL 文本里：" + rendered.getSql(), rendered.getSql().contains(attack));
        Assert.assertFalse(rendered.getSql().contains("OR '1'='1"));
        Assert.assertTrue("值应走参数绑定", rendered.getArgs().contains(attack));
    }

    @Test
    public void unknownField_isRejectedWithWarning() {
        SqlTemplateRenderer.Rendered rendered = SqlTemplateRenderer.render(
                PAYMENT_TEMPLATE,
                SqlTemplateRenderer.values("TS1", "TS2", null, 50),
                List.of(FieldFilter.eq("password", "x")),
                PAYMENT_FIELDS,
                SqlTemplateRenderer.Dialect.POSTGRES);

        Assert.assertFalse(rendered.getSql().contains("password"));
        Assert.assertTrue("应告警说明被忽略：" + rendered.getWarnings(),
                rendered.getWarnings().stream().anyMatch(w -> w.contains("不可过滤") || w.contains("不在字段字典")));
        Assert.assertEquals(3, rendered.getArgs().size());
    }

    @Test
    public void nonFilterableField_isRejectedWithWarning() {
        SqlTemplateRenderer.Rendered rendered = SqlTemplateRenderer.render(
                PAYMENT_TEMPLATE,
                SqlTemplateRenderer.values("TS1", "TS2", null, 50),
                List.of(FieldFilter.eq("duration_ms", "30012")),
                PAYMENT_FIELDS,
                SqlTemplateRenderer.Dialect.POSTGRES);

        Assert.assertFalse(rendered.getSql().contains("duration_ms"));
        Assert.assertEquals("只有 start/end/limit 三个参数", 3, rendered.getArgs().size());
    }

    @Test
    public void illegalIdentifier_isRejectedWithWarning() {
        SqlTemplateRenderer.Rendered rendered = SqlTemplateRenderer.render(
                PAYMENT_TEMPLATE,
                SqlTemplateRenderer.values("TS1", "TS2", null, 50),
                List.of(FieldFilter.eq("status; DROP TABLE payment_transaction", "x")),
                PAYMENT_FIELDS,
                SqlTemplateRenderer.Dialect.POSTGRES);

        Assert.assertFalse(rendered.getSql().toUpperCase().contains("DROP"));
        Assert.assertTrue(rendered.getWarnings().stream().anyMatch(w -> w.contains("非法")));
    }

    // ------------------------------------------------------------------ 操作符 / 类型转换

    @Test
    public void operators_areRenderedFromWhitelist() {
        SqlTemplateRenderer.Rendered rendered = SqlTemplateRenderer.render(
                PAYMENT_TEMPLATE,
                SqlTemplateRenderer.values("TS1", "TS2", null, 50),
                List.of(FieldFilter.builder().field("status").operator(FieldFilter.Operator.NE).value("SUCCESS").build(),
                        FieldFilter.builder().field("amount").operator(FieldFilter.Operator.GTE).value("100").build()),
                PAYMENT_FIELDS,
                SqlTemplateRenderer.Dialect.POSTGRES);

        Assert.assertTrue(rendered.getSql().contains("AND status <> ?"));
        Assert.assertTrue("数值字段必须带类型转换（PG 里 numeric = text 会直接报错）",
                rendered.getSql().contains("AND amount >= ?::numeric"));
        Assert.assertEquals(List.of("TS1", "TS2", "SUCCESS", "100", 50), rendered.getArgs());
    }

    @Test
    public void inOperator_expandsToPlaceholders() {
        SqlTemplateRenderer.Rendered rendered = SqlTemplateRenderer.render(
                PAYMENT_TEMPLATE,
                SqlTemplateRenderer.values("TS1", "TS2", null, 50),
                List.of(FieldFilter.in("pay_channel", "WECHAT, ALIPAY")),
                PAYMENT_FIELDS,
                SqlTemplateRenderer.Dialect.POSTGRES);

        Assert.assertTrue(rendered.getSql().contains("AND pay_channel IN (?, ?)"));
        Assert.assertEquals(List.of("TS1", "TS2", "WECHAT", "ALIPAY", 50), rendered.getArgs());
    }

    @Test
    public void typeCastsFollowDialect() {
        SqlTemplateRenderer.Rendered mysql = SqlTemplateRenderer.render(
                PAYMENT_TEMPLATE.replace("payment_transaction", "ai_order"),
                SqlTemplateRenderer.values("TS1", "TS2", null, 50),
                List.of(FieldFilter.builder().field("amount").operator(FieldFilter.Operator.GT).value("1").build()),
                PAYMENT_FIELDS,
                SqlTemplateRenderer.Dialect.MYSQL);
        Assert.assertTrue(mysql.getSql().contains("CAST(? AS DECIMAL"));

        SqlTemplateRenderer.Rendered blankValue = SqlTemplateRenderer.render(
                PAYMENT_TEMPLATE,
                SqlTemplateRenderer.values("TS1", "TS2", null, 50),
                List.of(FieldFilter.eq("status", "  ")),
                PAYMENT_FIELDS,
                SqlTemplateRenderer.Dialect.POSTGRES);
        Assert.assertFalse("空值过滤应被跳过", blankValue.getSql().contains("AND status"));
    }

    // ------------------------------------------------------------------ 未启用过滤 / 异常

    @Test
    public void templateWithoutFiltersPlaceholder_ignoresFiltersWithWarning() {
        SqlTemplateRenderer.Rendered rendered = SqlTemplateRenderer.render(
                ORDER_TEMPLATE,
                SqlTemplateRenderer.values("TS1", "TS2", null, 50),
                List.of(FieldFilter.eq("status", "FAILED")),
                List.of(field("status", "varchar", true)),
                SqlTemplateRenderer.Dialect.MYSQL);

        Assert.assertFalse(rendered.getSql().contains("status"));
        Assert.assertTrue("必须告警说明过滤被忽略：" + rendered.getWarnings(),
                rendered.getWarnings().stream().anyMatch(w -> w.contains("未启用通用过滤") && w.contains("status")));
    }

    @Test
    public void unknownPlaceholder_failsLoudly() {
        try {
            SqlTemplateRenderer.render("SELECT 1 WHERE a = ${weird}",
                    Map.of(), null, null, SqlTemplateRenderer.Dialect.MYSQL);
            Assert.fail("未知占位符必须报错");
        } catch (IllegalStateException e) {
            Assert.assertTrue(e.getMessage().contains("${weird}"));
        }
    }

    @Test
    public void blankTemplate_failsLoudly() {
        try {
            SqlTemplateRenderer.render("  ", Map.of(), null, null, SqlTemplateRenderer.Dialect.MYSQL);
            Assert.fail("空模板必须报错");
        } catch (IllegalStateException e) {
            Assert.assertTrue(e.getMessage().contains("query_template"));
        }
    }

    private static EntityMeta.FieldMeta field(String name, String type, boolean filterable) {
        return EntityMeta.FieldMeta.builder()
                .fieldName(name).fieldType(type).filterable(filterable).build();
    }

}
