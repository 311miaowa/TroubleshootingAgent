package cn.bugstack.ai.test.m3;

import cn.bugstack.ai.domain.agent.adapter.port.datasource.DataSourceCapability;
import cn.bugstack.ai.domain.agent.adapter.port.datasource.IDataSourceProvider;
import cn.bugstack.ai.domain.agent.adapter.port.datasource.IDataSourceRegistry;
import cn.bugstack.ai.domain.agent.adapter.port.datasource.ILogSearchProvider;
import cn.bugstack.ai.domain.agent.adapter.repository.IDataSourceRepository;
import cn.bugstack.ai.domain.agent.model.entity.ToolCallEventEntity;
import cn.bugstack.ai.domain.agent.model.valobj.datasource.EntityMeta;
import cn.bugstack.ai.domain.agent.model.valobj.datasource.HealthStatus;
import cn.bugstack.ai.domain.agent.model.valobj.datasource.LogSearchQuery;
import cn.bugstack.ai.domain.agent.model.valobj.datasource.QueryResult;
import cn.bugstack.ai.domain.agent.service.datasource.DataSourceQueryService;
import cn.bugstack.ai.domain.agent.service.datasource.tool.DataSourceToolCallbackFactory;
import cn.bugstack.ai.domain.agent.service.datasource.tool.ToolCallEventSink;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.junit.Assert;
import org.junit.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * M3.2 步骤 1 验证：rawRef 不进 Prompt。
 * <ol>
 *     <li>{@code QueryResult.toModelJson()} 剥离 rawRef 与行级 _rawRef；</li>
 *     <li>工具回调返回值（模型版）不含 rawRef，而事件版（sink 捕获）含 rawRef。</li>
 * </ol>
 *
 * @author miaowazhongzi
 */
@Slf4j
public class M3RawRefStripTest {

    @Test
    public void toModelJson_stripsRawRef() {
        QueryResult full = sampleQueryResult();
        String fullJson = JSON.toJSONString(full);
        String modelJson = full.toModelJson();

        log.info("完整版: {}", fullJson);
        log.info("模型版: {}", modelJson);

        JSONObject fullObj = JSON.parseObject(fullJson);
        JSONObject modelObj = JSON.parseObject(modelJson);

        Assert.assertNotNull("完整版应含 rawRef", fullObj.getString("rawRef"));
        Assert.assertNull("模型版 rawRef 应为 null", modelObj.getString("rawRef"));
        Assert.assertTrue("完整版首行应含 _rawRef",
                fullObj.getJSONArray("rows").getJSONObject(0).containsKey(QueryResult.RAW_REF_KEY));
        Assert.assertFalse("模型版首行不应含 _rawRef",
                modelObj.getJSONArray("rows").getJSONObject(0).containsKey(QueryResult.RAW_REF_KEY));
    }

    @Test
    public void toolCall_modelJsonStripsRawRef_eventKeepsRawRef() throws Exception {
        ToolCallback queryLogs = queryLogsCallback();

        List<ToolCallEventEntity> events = new ArrayList<>();
        ToolCallEventSink sink = new ToolCallEventSink("sess-m3-2", 1, events::add);

        Map<String, Object> ctxMap = new HashMap<>();
        ctxMap.put(ToolCallEventSink.TOOL_CONTEXT_KEY, sink);
        ToolContext toolContext = new ToolContext(ctxMap);

        String modelResult = queryLogs.call("{\"keyword\":\"限流\"}", toolContext);

        log.info("模型版返回值: {}", modelResult);
        Assert.assertEquals("应发出 start + result 两条事件", 2, events.size());

        JSONObject modelObj = JSON.parseObject(modelResult);
        Assert.assertNull("模型版 rawRef 应为 null", modelObj.getString("rawRef"));
        Assert.assertFalse("模型版首行不应含 _rawRef",
                modelObj.getJSONArray("rows").getJSONObject(0).containsKey(QueryResult.RAW_REF_KEY));

        String eventResult = events.get(1).getResult();
        log.info("事件版 result: {}", eventResult);
        JSONObject eventObj = JSON.parseObject(eventResult);
        Assert.assertNotNull("事件版应含 rawRef", eventObj.getString("rawRef"));
        Assert.assertTrue("事件版首行应含 _rawRef",
                eventObj.getJSONArray("rows").getJSONObject(0).containsKey(QueryResult.RAW_REF_KEY));
    }

    private QueryResult sampleQueryResult() {
        Map<String, Object> row = new HashMap<>();
        row.put("level", "ERROR");
        row.put("message", "触发限流");
        row.put(QueryResult.RAW_REF_KEY, "group-buy-market-log-2026.03.27#abc123");

        return QueryResult.builder()
                .dsId("ds_log_es")
                .entityCode("logs")
                .columns(List.of("level", "message"))
                .rows(List.of(row))
                .total(1)
                .rawRef("es://ds_log_es/logs/group-buy-market-log-*@2026-03-26T16:00:00Z~2026-03-27T16:00:00Z")
                .build();
    }

    private ToolCallback queryLogsCallback() throws Exception {
        DataSourceToolCallbackFactory factory = new DataSourceToolCallbackFactory();

        // mock queryService：search 返回含 rawRef 的 QueryResult
        DataSourceQueryService queryService = new DataSourceQueryService() {
            @Override
            public QueryResult search(String dsId, LogSearchQuery query) {
                return sampleQueryResult();
            }
        };

        injectField(factory, "dataSourceRegistry", registryWithLogProvider());
        injectField(factory, "dataSourceQueryService", queryService);
        injectField(factory, "dataSourceRepository", emptyRepository());

        List<ToolCallback> callbacks = factory.buildFor("test-client", null);
        return callbacks.stream()
                .filter(c -> "query_logs".equals(c.getToolDefinition().name()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("query_logs 工具缺失"));
    }

    private IDataSourceRegistry registryWithLogProvider() {
        IDataSourceProvider logProvider = new ILogSearchProvider() {
            @Override
            public String dsId() {
                return "ds_log_es";
            }

            @Override
            public String dsType() {
                return "elasticsearch";
            }

            @Override
            public Set<DataSourceCapability> capabilities() {
                return Set.of(DataSourceCapability.LOG_SEARCH);
            }

            @Override
            public HealthStatus healthCheck() {
                return null;
            }

            @Override
            public List<EntityMeta> listEntities() {
                return List.of(EntityMeta.builder()
                        .dsId("ds_log_es").entityCode("logs").capability("log_search").build());
            }

            @Override
            public QueryResult search(LogSearchQuery query) {
                return sampleQueryResult();
            }

            @Override
            public void close() {
            }
        };

        return new IDataSourceRegistry() {
            @Override
            public void register(IDataSourceProvider provider) {
            }

            @Override
            public void unregister(String dsId) {
            }

            @Override
            public Optional<IDataSourceProvider> get(String dsId) {
                return Optional.empty();
            }

            @Override
            public List<IDataSourceProvider> listByTenant(String tenantId) {
                return List.of(logProvider);
            }
        };
    }

    private IDataSourceRepository emptyRepository() {
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
                return Map.of();
            }

            @Override
            public List<EntityConfig> queryEnabledEntityConfigs(String dsId) {
                return List.of();
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

    private void injectField(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

}
