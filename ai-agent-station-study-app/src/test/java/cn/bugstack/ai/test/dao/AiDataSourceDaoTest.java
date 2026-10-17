package cn.bugstack.ai.test.dao;

import cn.bugstack.ai.domain.agent.adapter.repository.IDataSourceRepository;
import cn.bugstack.ai.domain.agent.model.valobj.datasource.EntityMeta;
import cn.bugstack.ai.infrastructure.dao.IAiDataSourceDao;
import cn.bugstack.ai.infrastructure.dao.IAiDataSourceEntityDao;
import cn.bugstack.ai.infrastructure.dao.po.AiDataSource;
import cn.bugstack.ai.infrastructure.dao.po.AiDataSourceEntity;
import lombok.extern.slf4j.Slf4j;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit4.SpringRunner;

import jakarta.annotation.Resource;
import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;
import java.util.List;
import java.util.Map;

/**
 * 统一数据源 DAO 与仓储测试（M1.0）
 * <p>
 * 覆盖编译期验证不了的三件事：
 * <ol>
 *     <li>mapper XML 的 resultMap 列映射正确（下划线 → 驼峰）；</li>
 *     <li>{@code status = 1} 过滤真的生效（用一条临时 status=0 数据做反例）；</li>
 *     <li>字段字典分组、sensitive 标记、timeField 回退优先级正确。</li>
 * </ol>
 * 说明：只读取种子数据；临时反例数据在 finally 中删除，不留残留。
 *
 * @author miaowazhongzi
 * 2025/6/27 18:29
 */
@Slf4j
@RunWith(SpringRunner.class)
@SpringBootTest
public class AiDataSourceDaoTest {

    @Resource
    private IAiDataSourceDao aiDataSourceDao;

    @Resource
    private IAiDataSourceEntityDao aiDataSourceEntityDao;

    @Resource
    private IDataSourceRepository dataSourceRepository;

    @Resource
    private DataSource mysqlDataSource;

    @Test
    public void test_queryEnabled_and_columnMapping() {
        List<AiDataSource> enabled = aiDataSourceDao.queryEnabled();
        Assert.assertNotNull("queryEnabled 不应返回 null", enabled);
        log.info("启用数据源数量: {}", enabled.size());
        for (AiDataSource ds : enabled) {
            log.info("  数据源: dsId={}, dsName={}, dsType={}, mcpId={}, maxRows={}, timeoutMs={}",
                    ds.getDsId(), ds.getDsName(), ds.getDsType(), ds.getMcpId(), ds.getMaxRows(), ds.getTimeoutMs());
        }

        AiDataSource es = aiDataSourceDao.queryByDsId("ds_log_es");
        Assert.assertNotNull("种子数据 ds_log_es 应存在", es);
        Assert.assertEquals("列映射 ds_name 失败", "ELK 日志源", es.getDsName());
        Assert.assertEquals("列映射 ds_type 失败", "elasticsearch", es.getDsType());
        Assert.assertEquals("列映射 mcp_id 失败", "5007", es.getMcpId());
        Assert.assertEquals("列映射 max_rows 失败", Integer.valueOf(200), es.getMaxRows());
        Assert.assertEquals("列映射 timeout_ms 失败", Integer.valueOf(5000), es.getTimeoutMs());
        Assert.assertEquals("列映射 tenant_id 失败", "default", es.getTenantId());
        Assert.assertEquals("列映射 time_field 失败", "@timestamp", es.getTimeField());
        Assert.assertNotNull("列映射 create_time 失败", es.getCreateTime());

        AiDataSource order = aiDataSourceDao.queryByDsId("ds_order_mysql");
        Assert.assertNotNull("种子数据 ds_order_mysql 应存在", order);
        Assert.assertTrue("conn_config 应含 jdbcUrl", order.getConnConfig().contains("jdbcUrl"));
    }

    @Test
    public void test_statusFilter_rejectsDisabled() {
        final String disabledDsId = "__tmp_disabled_ds__";
        try {
            executeSql("INSERT INTO ai_data_source (ds_id, ds_name, ds_type, tenant_id, max_rows, timeout_ms, status) "
                    + "VALUES ('" + disabledDsId + "', '临时禁用源', 'mysql', 'default', 10, 1000, 0)");

            // 反例：status=0 的行不得出现在任何"启用"查询里
            for (AiDataSource ds : aiDataSourceDao.queryEnabled()) {
                Assert.assertNotEquals("status=0 的数据源泄漏进 queryEnabled", disabledDsId, ds.getDsId());
            }

            // 正面：不过滤 status 的查询能查到它，证明数据确实存在（排除"插入失败"这一解释）
            AiDataSource raw = aiDataSourceDao.queryByDsId(disabledDsId);
            Assert.assertNotNull("临时数据应已插入（证明是过滤生效而非插入失败）", raw);
            log.info("反例验证通过：status=0 的 {} 存在，但被 queryEnabled 过滤", disabledDsId);

            // 仓储层同样不得返回
            Map<String, IDataSourceRepository.DataSourceConfig> configs = dataSourceRepository.queryEnabledDataSources();
            Assert.assertFalse("status=0 的数据源泄漏进 queryEnabledDataSources", configs.containsKey(disabledDsId));
        } finally {
            executeSqlQuietly("DELETE FROM ai_data_source WHERE ds_id = '" + disabledDsId + "'");
        }
    }

    @Test
    public void test_entitiesAndFields() {
        List<AiDataSourceEntity> entities = aiDataSourceEntityDao.queryEnabledByDsId("ds_log_es");
        Assert.assertEquals("ds_log_es 应有 1 个启用实体", 1, entities.size());
        Assert.assertEquals("列映射 entity_code 失败", "logs", entities.get(0).getEntityCode());
        Assert.assertEquals("列映射 capability 失败", "log_search", entities.get(0).getCapability());
        Assert.assertNotNull("列映射 query_template 失败", entities.get(0).getQueryTemplate());

        List<EntityMeta> metas = dataSourceRepository.queryEnabledEntities("ds_log_es");
        Assert.assertEquals(1, metas.size());
        EntityMeta logMeta = metas.get(0);
        Assert.assertEquals("timeField 应优先取字典中 is_time_field=1 的字段", "@timestamp", logMeta.getTimeField());
        Assert.assertEquals("log 字段字典应为 4 个字段", 4, logMeta.getFields().size());

        Map<String, List<EntityMeta.FieldMeta>> orderFields = dataSourceRepository.queryEnabledFields("ds_order_mysql");
        List<EntityMeta.FieldMeta> fields = orderFields.get("order");
        Assert.assertNotNull("order 实体的字段字典应存在", fields);
        Assert.assertEquals("order 字段数", 6, fields.size());

        EntityMeta.FieldMeta userId = null;
        for (EntityMeta.FieldMeta f : fields) {
            if ("user_id".equals(f.getFieldName())) {
                userId = f;
                break;
            }
        }
        Assert.assertNotNull("user_id 字段缺失", userId);
        Assert.assertTrue("user_id 的 sensitive 标记应映射为 true", userId.isSensitive());
        Assert.assertEquals("列映射 field_label 失败", "用户ID", userId.getFieldLabel());
        log.info("字段字典映射验证通过，user_id sensitive={}", userId.isSensitive());
    }

    @Test
    public void test_stageToolPolicy_analyzerMustNotGetExecutionCapabilities() {
        List<String> analyzer = dataSourceRepository.queryAllowedCapabilities("TASK_ANALYZER_CLIENT");
        log.info("TASK_ANALYZER_CLIENT 允许的能力: {}", analyzer);
        Assert.assertTrue("分析阶段应允许 entity_meta", analyzer.contains("entity_meta"));
        Assert.assertFalse("分析阶段不得拥有 log_search（否则四步链会被短路）", analyzer.contains("log_search"));
        Assert.assertFalse("分析阶段不得拥有 entity_query", analyzer.contains("entity_query"));

        List<String> executor = dataSourceRepository.queryAllowedCapabilities("PRECISION_EXECUTOR_CLIENT");
        log.info("PRECISION_EXECUTOR_CLIENT 允许的能力: {}", executor);
        Assert.assertTrue("执行阶段应允许 log_search", executor.contains("log_search"));
        Assert.assertTrue("执行阶段应允许 entity_query", executor.contains("entity_query"));

        List<String> supervisor = dataSourceRepository.queryAllowedCapabilities("QUALITY_SUPERVISOR_CLIENT");
        log.info("QUALITY_SUPERVISOR_CLIENT 允许的能力: {}", supervisor);
        Assert.assertFalse("监督阶段不得拥有 log_search", supervisor.contains("log_search"));

        List<String> assistant = dataSourceRepository.queryAllowedCapabilities("RESPONSE_ASSISTANT");
        log.info("RESPONSE_ASSISTANT 允许的能力: {}", assistant);
        Assert.assertTrue("响应助手不应暴露任何执行能力", assistant.isEmpty());

        Map<String, List<String>> all = dataSourceRepository.queryStageToolPolicy();
        // RESPONSE_ASSISTANT 三项全为 allowed=0，按"只回传白名单"的语义它不会成为 key，
        // 因此这里应是 3 个阶段（分析/执行/监督），而不是配置表里的 4 个 stage_code。
        Assert.assertEquals("白名单应覆盖 3 个阶段（RESPONSE_ASSISTANT 全为拒绝）", 3, all.size());
        Assert.assertFalse("全为 allowed=0 的阶段不应出现在白名单里", all.containsKey("RESPONSE_ASSISTANT"));
    }

    private void executeSql(String sql) {
        try (Connection conn = mysqlDataSource.getConnection(); Statement st = conn.createStatement()) {
            st.executeUpdate(sql);
        } catch (Exception e) {
            throw new IllegalStateException("执行测试 SQL 失败: " + sql, e);
        }
    }

    private void executeSqlQuietly(String sql) {
        try (Connection conn = mysqlDataSource.getConnection(); Statement st = conn.createStatement()) {
            st.executeUpdate(sql);
        } catch (Exception e) {
            log.warn("清理测试 SQL 失败（不影响断言结果）: {}", sql, e);
        }
    }

}
