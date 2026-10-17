package cn.bugstack.ai.test.m3;

import cn.bugstack.ai.domain.agent.adapter.port.datasource.IDataSourceProvider;
import cn.bugstack.ai.domain.agent.adapter.port.datasource.IDataSourceRegistry;
import cn.bugstack.ai.domain.agent.adapter.repository.IDataSourceRepository;
import cn.bugstack.ai.domain.agent.model.entity.ToolCallEventEntity;
import cn.bugstack.ai.domain.agent.model.valobj.datasource.EntityMeta;
import cn.bugstack.ai.domain.agent.service.datasource.DataSourceQueryService;
import cn.bugstack.ai.domain.agent.service.datasource.tool.DataSourceToolCallbackFactory;
import cn.bugstack.ai.domain.agent.service.datasource.tool.ToolCallEventSink;
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

/**
 * M3.1 验证：语义工具回调在双参 {@code call(toolInput, toolContext)} 里实时发出
 * start / result 两条结构化事件；单参 {@code call} 无 sink 时静默（不抛异常）。
 * <p>
 * 不依赖 Spring 上下文：反射注入工厂依赖，用 describe_entity（空 registry 也能跑）验证事件机制。
 *
 * @author miaowazhongzi
 */
@Slf4j
public class M3ToolCallEventTest {

    @Test
    public void doubleArgCall_emitsStartAndResultEvents() throws Exception {
        ToolCallback describeEntity = describeEntityCallback();

        List<ToolCallEventEntity> events = new ArrayList<>();
        ToolCallEventSink sink = new ToolCallEventSink("sess-m3-1", 2, events::add);

        Map<String, Object> ctxMap = new HashMap<>();
        ctxMap.put(ToolCallEventSink.TOOL_CONTEXT_KEY, sink);
        ToolContext toolContext = new ToolContext(ctxMap);

        String result = describeEntity.call("{}", toolContext);

        log.info("describe_entity 双参 call 结果: {}", result);
        log.info("捕获事件数: {}", events.size());

        Assert.assertEquals("应发出 start + result 两条事件", 2, events.size());

        ToolCallEventEntity start = events.get(0);
        ToolCallEventEntity resultEvent = events.get(1);
        Assert.assertEquals("首条应为 start", ToolCallEventEntity.SUBTYPE_START, start.getSubType());
        Assert.assertEquals("次条应为 result", ToolCallEventEntity.SUBTYPE_RESULT, resultEvent.getSubType());
        Assert.assertEquals("事件类型应为 tool_call", ToolCallEventEntity.TYPE, start.getType());
        Assert.assertEquals("toolName 应为 describe_entity", "describe_entity", start.getToolName());
        Assert.assertEquals("sessionId 应透传", "sess-m3-1", start.getSessionId());
        Assert.assertEquals("step 应透传", Integer.valueOf(2), start.getStep());
        Assert.assertNotNull("result 事件应带返回内容", resultEvent.getResult());
        log.info("start 事件: toolName={} step={} sessionId={}", start.getToolName(), start.getStep(), start.getSessionId());
        log.info("result 事件: result={}", resultEvent.getResult());
    }

    @Test
    public void singleArgCall_noSink_isSilent() throws Exception {
        ToolCallback describeEntity = describeEntityCallback();
        // 无 sink：单参 call 直接执行，不发事件、不抛异常
        String result = describeEntity.call("{}");
        log.info("describe_entity 单参 call 结果: {}", result);
        Assert.assertNotNull("单参 call 应正常返回", result);
    }

    @Test
    public void doubleArgCall_emptyToolContext_noEvent_noException() throws Exception {
        ToolCallback describeEntity = describeEntityCallback();
        // 反例：toolContext 里没有 sink（等价于没注入 .toolContext），应不发事件且正常返回
        ToolContext emptyContext = new ToolContext(new HashMap<>());
        String result = describeEntity.call("{}", emptyContext);
        log.info("describe_entity 双参 call（空 toolContext）结果: {}", result);
        Assert.assertNotNull("无 sink 时应正常返回", result);
    }

    private ToolCallback describeEntityCallback() throws Exception {
        DataSourceToolCallbackFactory factory = new DataSourceToolCallbackFactory();
        injectField(factory, "dataSourceRegistry", emptyRegistry());
        injectField(factory, "dataSourceQueryService", new DataSourceQueryService());
        injectField(factory, "dataSourceRepository", emptyRepository());

        List<ToolCallback> callbacks = factory.buildFor("test-client", null);
        return callbacks.stream()
                .filter(c -> "describe_entity".equals(c.getToolDefinition().name()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("describe_entity 工具缺失"));
    }

    private IDataSourceRegistry emptyRegistry() {
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
                return List.of();
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
