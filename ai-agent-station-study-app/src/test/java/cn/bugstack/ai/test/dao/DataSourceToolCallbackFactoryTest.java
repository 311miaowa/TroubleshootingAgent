package cn.bugstack.ai.test.dao;

import cn.bugstack.ai.domain.agent.model.valobj.datasource.QueryResult;
import cn.bugstack.ai.domain.agent.service.datasource.tool.DataSourceToolCallbackFactory;
import com.alibaba.fastjson.JSON;
import lombok.extern.slf4j.Slf4j;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit4.SpringRunner;

import jakarta.annotation.Resource;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 数据源语义工具工厂集成测试（M1.1）
 * <p>
 * 验证两件事：
 * <ol>
 *     <li>模型可见工具只有语义工具（query_logs / describe_entity），不含任何写/删类工具；</li>
 *     <li>{@code query_logs} 语义工具全链路（工厂 → 门面 → Provider → ES MCP）命中真实 ES 数据。</li>
 * </ol>
 * <p>
 * 前置：ES 已启动（127.0.0.1:9200）且有日志索引；ES MCP（5007）已启用；
 * 种子已用 docs/dev-ops/mysql/sql/upgrade-m1-1-align-es-log-entity.sql 对齐真实索引。
 *
 * @author miaowazhongzi
 * 2025/6/27 18:29
 */
@Slf4j
@RunWith(SpringRunner.class)
@SpringBootTest
public class DataSourceToolCallbackFactoryTest {

    @Resource
    private DataSourceToolCallbackFactory factory;

    @Resource
    private javax.sql.DataSource mysqlDataSource;

    @Test
    public void test_onlySemanticTools_noWriteDeleteTools() {
        List<ToolCallback> callbacks = factory.buildFor("3102", null);
        List<String> names = callbacks.stream()
                .map(c -> c.getToolDefinition().name())
                .collect(Collectors.toList());

        log.info("模型可见工具: {}", names);

        Assert.assertTrue("应包含 query_logs", names.contains("query_logs"));
        Assert.assertTrue("应包含 describe_entity", names.contains("describe_entity"));

        // 写/删类工具（原始 ES MCP 暴露过的 6 个）一个都不能出现
        for (String name : names) {
            Assert.assertFalse("模型不应看到写/删工具: " + name, isWriteOrDeleteTool(name));
        }
    }

    @Test
    public void test_queryLogs_hitsRealEsData() {
        ToolCallback queryLogs = factory.buildFor("3102", null).stream()
                .filter(c -> "query_logs".equals(c.getToolDefinition().name()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("query_logs 工具缺失"));

        // keyword=限流 应对真实索引命中（group-buy-market-log 里有 2 条含"限流"的日志）
        // 注意：必须传时间窗——provider 默认"最近 1 小时"（系统时钟 2026-09），而日志是 2026-03 的
        String resultJson = queryLogs.call(
                "{\"keyword\":\"限流\",\"limit\":20,\"startTime\":\"2026-03-27T00:00:00+08:00\",\"endTime\":\"2026-03-28T00:00:00+08:00\"}");
        QueryResult result = JSON.parseObject(resultJson, QueryResult.class);

        log.info("query_logs 结果: dsId={} entityCode={} rows={} columns={} truncated={} warnings={}",
                result.getDsId(), result.getEntityCode(),
                result.getRows() == null ? 0 : result.getRows().size(),
                result.getColumns(), result.isTruncated(), result.getWarnings());

        Assert.assertEquals("dsId 应为 ds_log_es", "ds_log_es", result.getDsId());
        Assert.assertTrue("query_logs 应命中真实 ES 数据", result.getRows() != null && result.getRows().size() > 0);
        Assert.assertTrue("warnings 应为空", result.getWarnings() == null || result.getWarnings().isEmpty());
    }

    @Test
    public void test_stagePruning() {
        // 分析阶段：只有 describe_entity，无 query_logs（否则四步链会被短路）
        List<String> analyzer = toolNames("TASK_ANALYZER_CLIENT");
        log.info("TASK_ANALYZER_CLIENT 工具: {}", analyzer);
        Assert.assertFalse("分析阶段不得有 query_logs", analyzer.contains("query_logs"));
        Assert.assertTrue("分析阶段应有 describe_entity", analyzer.contains("describe_entity"));

        // 执行阶段：describe_entity + query_logs
        List<String> executor = toolNames("PRECISION_EXECUTOR_CLIENT");
        log.info("PRECISION_EXECUTOR_CLIENT 工具: {}", executor);
        Assert.assertTrue("执行阶段应有 query_logs", executor.contains("query_logs"));
        Assert.assertTrue("执行阶段应有 describe_entity", executor.contains("describe_entity"));

        // 监督阶段：只有 describe_entity，无 query_logs
        List<String> supervisor = toolNames("QUALITY_SUPERVISOR_CLIENT");
        log.info("QUALITY_SUPERVISOR_CLIENT 工具: {}", supervisor);
        Assert.assertFalse("监督阶段不得有 query_logs", supervisor.contains("query_logs"));
        Assert.assertTrue("监督阶段应有 describe_entity", supervisor.contains("describe_entity"));

        // 响应助手：无任何工具
        List<String> assistant = toolNames("RESPONSE_ASSISTANT");
        log.info("RESPONSE_ASSISTANT 工具: {}", assistant);
        Assert.assertTrue("响应助手不应有任何工具", assistant.isEmpty());
    }

    @Test
    public void test_queryOrders_hitsRealMysqlData() {
        ToolCallback queryOrders = factory.buildFor("3102", "PRECISION_EXECUTOR_CLIENT").stream()
                .filter(c -> "query_orders".equals(c.getToolDefinition().name()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("query_orders 工具缺失（执行阶段应有 entity_query）"));

        // 时间窗必须覆盖样例数据（2026-03-27）
        String resultJson = queryOrders.call(
                "{\"limit\":20,\"startTime\":\"2026-03-27T00:00:00+08:00\",\"endTime\":\"2026-03-28T00:00:00+08:00\"}");
        QueryResult result = JSON.parseObject(resultJson, QueryResult.class);

        log.info("query_orders 结果: dsId={} entityCode={} rows={} columns={} warnings={}",
                result.getDsId(), result.getEntityCode(),
                result.getRows() == null ? 0 : result.getRows().size(),
                result.getColumns(), result.getWarnings());

        Assert.assertEquals("dsId 应为 ds_order_mysql", "ds_order_mysql", result.getDsId());
        Assert.assertEquals("entityCode 应为 orders", "orders", result.getEntityCode());
        Assert.assertTrue("query_orders 应命中真实 MySQL 数据", result.getRows() != null && result.getRows().size() > 0);
        Assert.assertTrue("warnings 应为空", result.getWarnings() == null || result.getWarnings().isEmpty());

        // 敏感字段 user_id 应被脱敏
        Map<String, Object> firstRow = result.getRows().get(0);
        Object userId = firstRow.get("user_id");
        Assert.assertNotNull("首行应含 user_id 字段", userId);
        Assert.assertTrue("user_id 应被脱敏（含 ****）", userId.toString().contains("****"));
        log.info("query_orders 首行 user_id（脱敏后）: {}", userId);
    }

    @Test
    public void test_counterExample_allAllowedPolicyExposesQueryLogsToAnalyzer() throws Exception {
        // 反例验证（M1.4）：证明 ai_stage_tool_policy 是"四步链短路/修复"的因果杠杆。
        // 1. 正确策略：分析阶段不得有 query_logs
        Assert.assertFalse("正确策略下分析阶段不应有 query_logs",
                toolNames("TASK_ANALYZER_CLIENT").contains("query_logs"));

        // 2. 反例：把分析阶段的 log_search 改为允许（模拟"改回全允许"）
        executeSql("UPDATE ai_stage_tool_policy SET allowed = 1 "
                + "WHERE stage_code = 'TASK_ANALYZER_CLIENT' AND capability = 'log_search'");
        try {
            List<String> analyzer = toolNames("TASK_ANALYZER_CLIENT");
            log.info("反例（全允许）分析阶段工具: {}", analyzer);
            Assert.assertTrue("全允许后分析阶段应拿到 query_logs（这是四步链被短路的机制）",
                    analyzer.contains("query_logs"));
        } finally {
            // 3. 恢复正确策略
            executeSql("UPDATE ai_stage_tool_policy SET allowed = 0 "
                    + "WHERE stage_code = 'TASK_ANALYZER_CLIENT' AND capability = 'log_search'");
        }

        // 4. 恢复后：分析阶段重新失去 query_logs（修复重新生效）
        Assert.assertFalse("恢复策略后分析阶段应重新失去 query_logs",
                toolNames("TASK_ANALYZER_CLIENT").contains("query_logs"));
        log.info("反例验证通过：策略是四步链短路的因果杠杆（改回全允许→短路，恢复→完整）");
    }

    private void executeSql(String sql) {
        try (java.sql.Connection conn = mysqlDataSource.getConnection();
             java.sql.Statement st = conn.createStatement()) {
            st.executeUpdate(sql);
        } catch (Exception e) {
            throw new IllegalStateException("执行反例验证 SQL 失败: " + sql, e);
        }
    }

    private List<String> toolNames(String clientType) {
        return factory.buildFor("test", clientType).stream()
                .map(c -> c.getToolDefinition().name())
                .collect(Collectors.toList());
    }

    private boolean isWriteOrDeleteTool(String name) {
        return name.contains("create_index")
                || name.contains("create_mapping")
                || name.contains("bulk")
                || name.contains("reindex")
                || name.contains("delete_index")
                || name.contains("index_template");
    }

}
