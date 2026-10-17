package cn.bugstack.ai.test.m3;

import cn.bugstack.ai.domain.agent.adapter.repository.IDataSourceRepository;
import cn.bugstack.ai.domain.agent.model.valobj.datasource.EntityMeta;
import cn.bugstack.ai.domain.agent.model.valobj.datasource.EntityQuery;
import cn.bugstack.ai.domain.agent.model.valobj.datasource.LogSearchQuery;
import cn.bugstack.ai.domain.agent.model.valobj.datasource.QueryResult;
import cn.bugstack.ai.domain.agent.model.valobj.datasource.TimeRange;
import cn.bugstack.ai.infrastructure.gateway.datasource.ElasticsearchProvider;
import cn.bugstack.ai.infrastructure.gateway.datasource.MysqlOrderProvider;
import lombok.extern.slf4j.Slf4j;
import org.junit.Assert;
import org.junit.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * M3.0 验证：两个 Provider 的 rawRef 落地（不依赖 Spring 上下文，直接 new Provider + 匿名仓储）。
 * <p>
 * 前置：ES 已启动（127.0.0.1:9200）且 group-buy-market-log-* 有日志数据；
 * MySQL 已启动且 ai_order 有样例数据；环境变量 DS_ORDER_MYSQL_PASSWORD 已设置。
 *
 * @author miaowazhongzi
 */
@Slf4j
public class M3RawRefProviderTest {

    private static final String ES_QUERY_TEMPLATE =
            "{\"query\":{\"bool\":{\"must\":[{\"range\":{\"@timestamp\":{\"gte\":\"${start}\",\"lte\":\"${end}\"}}},"
                    + "{\"match\":{\"message\":\"${keyword}\"}}]}},\"sort\":[{\"@timestamp\":{\"order\":\"desc\"}}],\"size\":${size}}";

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
    public void es_search_rawRef() {
        ElasticsearchProvider provider = new ElasticsearchProvider("ds_log_es",
                IDataSourceRepository.DataSourceConfig.builder()
                        .dsId("ds_log_es").dsType("elasticsearch").maxRows(200).timeoutMs(5000)
                        .connConfig("{\"host\":\"http://127.0.0.1:9200\"}")
                        .build(),
                repo());

        QueryResult result = provider.search(LogSearchQuery.builder()
                .keyword("限流").limit(20).timeRange(RANGE).build());

        log.info("ES rawRef={} rows={} columns={} warnings={}",
                result.getRawRef(), result.getRows() == null ? 0 : result.getRows().size(),
                result.getColumns(), result.getWarnings());

        Assert.assertTrue("ES 应命中数据，warnings=" + result.getWarnings(),
                result.getWarnings() == null || result.getWarnings().isEmpty());
        Assert.assertTrue("ES 应命中数据", result.getRows() != null && !result.getRows().isEmpty());
        Assert.assertNotNull("结果级 rawRef 非空", result.getRawRef());
        Assert.assertTrue("rawRef 应为 es:// 开头: " + result.getRawRef(), result.getRawRef().startsWith("es://"));

        for (Map<String, Object> row : result.getRows()) {
            String ref = (String) row.get(QueryResult.RAW_REF_KEY);
            Assert.assertNotNull("每行应有 _rawRef", ref);
            Assert.assertTrue("_rawRef 应为 index#id: " + ref, ref.matches(".+#.+"));
            log.info("  row._rawRef={} @timestamp={} level={} application={}",
                    ref, row.get("@timestamp"), row.get("level"), row.get("application"));
        }
        Assert.assertFalse("columns 不应含 _rawRef", result.getColumns().contains(QueryResult.RAW_REF_KEY));
    }

    @Test
    public void mysql_query_rawRef() {
        MysqlOrderProvider provider = new MysqlOrderProvider("ds_order_mysql",
                IDataSourceRepository.DataSourceConfig.builder()
                        .dsId("ds_order_mysql").dsType("mysql").maxRows(200).timeoutMs(5000)
                        .connConfig(MYSQL_CONN)
                        .build(),
                repo());

        QueryResult result = provider.query(EntityQuery.builder()
                .entityCode("orders").limit(20).timeRange(RANGE).build());

        log.info("MySQL rawRef={} rows={} columns={} warnings={}",
                result.getRawRef(), result.getRows() == null ? 0 : result.getRows().size(),
                result.getColumns(), result.getWarnings());

        Assert.assertTrue("MySQL 应命中数据，warnings=" + result.getWarnings(),
                result.getWarnings() == null || result.getWarnings().isEmpty());
        Assert.assertTrue("MySQL 应命中数据", result.getRows() != null && !result.getRows().isEmpty());
        Assert.assertNotNull("结果级 rawRef 非空", result.getRawRef());
        Assert.assertTrue("rawRef 应为 mysql:// 开头: " + result.getRawRef(), result.getRawRef().startsWith("mysql://"));

        for (Map<String, Object> row : result.getRows()) {
            String ref = (String) row.get(QueryResult.RAW_REF_KEY);
            Assert.assertNotNull("每行应有 _rawRef", ref);
            Assert.assertTrue("_rawRef 应为 order_no= 开头: " + ref, ref.startsWith("order_no="));
            log.info("  row._rawRef={} order_no={} status={}", ref, row.get("order_no"), row.get("status"));
        }
        Assert.assertFalse("columns 不应含 _rawRef", result.getColumns().contains(QueryResult.RAW_REF_KEY));
    }

    private IDataSourceRepository repo() {
        Map<String, List<EntityMeta.FieldMeta>> fields = new HashMap<>();
        fields.put("logs", List.of(
                f("@timestamp", false), f("level", false), f("message", false), f("application", false)));
        fields.put("orders", List.of(
                f("order_no", false), f("user_id", true), f("status", false),
                f("amount", false), f("fail_reason", false), f("create_time", false)));

        return new IDataSourceRepository() {
            @Override
            public Map<String, IDataSourceRepository.DataSourceConfig> queryEnabledDataSources() {
                return Map.of();
            }

            @Override
            public Map<String, IDataSourceRepository.DataSourceConfig> queryEnabledDataSources(String tenantId) {
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
            public List<IDataSourceRepository.EntityConfig> queryEnabledEntityConfigs(String dsId) {
                if ("ds_log_es".equals(dsId)) {
                    return List.of(IDataSourceRepository.EntityConfig.builder()
                            .dsId(dsId).entityCode("logs").capability("log_search")
                            .physicalName("group-buy-market-log-*").queryTemplate(ES_QUERY_TEMPLATE).build());
                }
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
