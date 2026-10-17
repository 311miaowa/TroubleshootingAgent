package cn.bugstack.ai.test.m3;

import cn.bugstack.ai.domain.agent.adapter.repository.IDataSourceRepository;
import cn.bugstack.ai.domain.agent.model.valobj.datasource.EntityMeta;
import cn.bugstack.ai.domain.agent.model.valobj.datasource.EntityQuery;
import cn.bugstack.ai.domain.agent.model.valobj.datasource.QueryResult;
import cn.bugstack.ai.domain.agent.model.valobj.datasource.TimeRange;
import cn.bugstack.ai.infrastructure.gateway.datasource.MysqlOrderProvider;
import lombok.extern.slf4j.Slf4j;
import org.junit.Assert;
import org.junit.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * M3.2 步骤 2 验证：订单号精确过滤接线。
 * <p>
 * 前置：MySQL 已启动且 ai_order 有样例数据；环境变量 DS_ORDER_MYSQL_PASSWORD 已设置；
 * ai_data_source_entity 的 query_template 已含 ${orderNo} 占位符（upgrade-m3-2-order-filter.sql）。
 *
 * @author miaowazhongzi
 */
@Slf4j
public class M3OrderFilterTest {

    private static final String MYSQL_QUERY_TEMPLATE =
            "SELECT order_no, user_id, status, amount, fail_reason, create_time FROM ai_order "
                    + "WHERE create_time >= ${start} AND create_time <= ${end} "
                    + "AND (order_no = ${orderNo} OR ${orderNo} IS NULL) "
                    + "ORDER BY create_time DESC LIMIT ${limit}";

    private static final String MYSQL_CONN =
            "{\"jdbcUrl\":\"jdbc:mysql://127.0.0.1:3306/ai-agent-station-study?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true\","
                    + "\"username\":\"ai_ro\",\"passwordEnv\":\"DS_ORDER_MYSQL_PASSWORD\",\"driverClassName\":\"com.mysql.cj.jdbc.Driver\"}";

    private static final TimeRange RANGE = TimeRange.of(
            Instant.parse("2026-03-27T00:00:00+08:00"),
            Instant.parse("2026-03-28T00:00:00+08:00"));

    @Test
    public void orderNo_filter_hitsSpecificOrder() {
        QueryResult result = query(EntityQuery.builder()
                .entityCode("orders").orderNo("NO20260327001").limit(20).timeRange(RANGE).build());

        log.info("order_no=NO20260327001 过滤结果 rows={} warnings={}", result.getRows().size(), result.getWarnings());
        Assert.assertTrue("应无告警: " + result.getWarnings(), result.getWarnings() == null || result.getWarnings().isEmpty());
        Assert.assertEquals("按订单号应精确命中 1 条", 1, result.getRows().size());
        Assert.assertEquals("命中的订单号应一致", "NO20260327001", result.getRows().get(0).get("order_no"));
    }

    @Test
    public void orderNo_null_noFilter() {
        QueryResult result = query(EntityQuery.builder()
                .entityCode("orders").limit(20).timeRange(RANGE).build());

        log.info("无 order_no 过滤结果 rows={} warnings={}", result.getRows().size(), result.getWarnings());
        Assert.assertTrue("应无告警: " + result.getWarnings(), result.getWarnings() == null || result.getWarnings().isEmpty());
        Assert.assertEquals("不过滤应返回全部 4 条样例订单", 4, result.getRows().size());
    }

    @Test
    public void orderNo_notFound_empty() {
        QueryResult result = query(EntityQuery.builder()
                .entityCode("orders").orderNo("NO_NOT_EXIST").limit(20).timeRange(RANGE).build());

        log.info("order_no=NO_NOT_EXIST 过滤结果 rows={} warnings={}", result.getRows().size(), result.getWarnings());
        Assert.assertTrue("应无告警: " + result.getWarnings(), result.getWarnings() == null || result.getWarnings().isEmpty());
        Assert.assertTrue("不存在的订单号应返回空", result.getRows().isEmpty());
    }

    private QueryResult query(EntityQuery query) {
        MysqlOrderProvider provider = new MysqlOrderProvider("ds_order_mysql",
                IDataSourceRepository.DataSourceConfig.builder()
                        .dsId("ds_order_mysql").dsType("mysql").maxRows(200).timeoutMs(5000)
                        .connConfig(MYSQL_CONN)
                        .build(),
                repo());
        return provider.query(query);
    }

    private IDataSourceRepository repo() {
        Map<String, List<EntityMeta.FieldMeta>> fields = new HashMap<>();
        fields.put("orders", List.of(
                f("order_no", false), f("user_id", true), f("status", false),
                f("amount", false), f("fail_reason", false), f("create_time", false)));

        return new IDataSourceRepository() {
            @Override
            public Map<String, DataSourceConfig> queryEnabledDataSources() {
                return Map.of();
            }

            @Override
            public Map<String, DataSourceConfig> queryEnabledDataSources(String tenantId) {
                return Map.of();
            }

            @Override
            public List<EntityMeta> queryEnabledEntities(String dsId) {
                return List.of();
            }

            @Override
            public Map<String, List<EntityMeta.FieldMeta>> queryEnabledFields(String dsId) {
                return fields;
            }

            @Override
            public List<EntityConfig> queryEnabledEntityConfigs(String dsId) {
                return List.of(IDataSourceRepository.EntityConfig.builder()
                        .dsId(dsId).entityCode("orders").capability("entity_query")
                        .physicalName("ai_order").queryTemplate(MYSQL_QUERY_TEMPLATE).build());
            }

            @Override
            public Map<String, List<String>> queryStageToolPolicy() {
                return Map.of();
            }

            @Override
            public List<String> queryAllowedCapabilities(String stageCode) {
                return List.of();
            }
        };
    }

    private EntityMeta.FieldMeta f(String name, boolean sensitive) {
        return EntityMeta.FieldMeta.builder().fieldName(name).sensitive(sensitive).build();
    }

}
