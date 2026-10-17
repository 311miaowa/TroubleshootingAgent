package cn.bugstack.ai.test.p2;

import cn.bugstack.ai.domain.agent.adapter.port.datasource.DataSourceCapability;
import cn.bugstack.ai.domain.agent.adapter.port.datasource.IDataSourceProvider;
import cn.bugstack.ai.domain.agent.adapter.port.datasource.IDataSourceRegistry;
import cn.bugstack.ai.domain.agent.adapter.port.datasource.ILogSearchProvider;
import cn.bugstack.ai.domain.agent.adapter.repository.IDataSourceRepository;
import cn.bugstack.ai.domain.agent.model.valobj.datasource.EntityMeta;
import cn.bugstack.ai.domain.agent.service.datasource.tool.DataSourceToolCallbackFactory;
import org.junit.Assert;
import org.junit.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * P2 工具重名检测单测
 * <p>
 * 场景：两个数据源都暴露了 {@code entity_code=logs}，工厂会拼出两个 {@code query_logs}。
 * Spring AI 的工具表按名字索引，"模型调到哪一个"会变成不可预期 —— 因此必须**保留首个 + 丢弃后续**。
 * 这个用例直接把这种配置喂给工厂，断言最终工具列表里 {@code query_logs} 只出现一次。
 *
 * @author miaowazhongzi
 */
public class DuplicateToolNameTest {

    private static final String ENTITY_LOGS = "logs";

    @Test
    public void duplicateEntityCode_yieldsSingleTool() throws Exception {
        DataSourceToolCallbackFactory factory = new DataSourceToolCallbackFactory();
        inject(factory, "dataSourceRegistry", registryWithTwoLogProviders());
        inject(factory, "dataSourceRepository", repositoryAllowing(List.of("log_search", "entity_meta")));

        List<String> names = factory.buildFor("4103", "PRECISION_EXECUTOR_CLIENT").stream()
                .map(c -> c.getToolDefinition().name())
                .collect(Collectors.toList());

        Assert.assertEquals("同名工具应只保留一个：" + names,
                1, names.stream().filter("query_logs"::equals).count());

        // 保留哪一个必须确定：装配前按 dsId 排序，因此恒为 dsId 字典序最小的源（ds_log_a）。
        // 用 entityDesc 区分两个源，避免只断言"剩一个"而放过"随机保留"的实现。
        String description = factory.buildFor("4103", "PRECISION_EXECUTOR_CLIENT").stream()
                .filter(c -> "query_logs".equals(c.getToolDefinition().name()))
                .findFirst()
                .map(c -> c.getToolDefinition().description())
                .orElse("");
        Assert.assertTrue("应保留 dsId 字典序最小的源（ds_log_a）的工具：" + description,
                description.contains("日志源A"));
        Assert.assertTrue("其余工具不应被误删（describe_entity 仍在）：" + names, names.contains("describe_entity"));
    }

    // ------------------------------------------------------------------ 夹具

    private IDataSourceRegistry registryWithTwoLogProviders() {
        IDataSourceProvider a = logProvider("ds_log_a");
        IDataSourceProvider b = logProvider("ds_log_b");
        return (IDataSourceRegistry) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{IDataSourceRegistry.class},
                (proxy, method, args) -> "listByTenant".equals(method.getName())
                        ? List.of(a, b) : defaultValue(method.getReturnType()));
    }

    /** 两个 Provider 只有 dsId 不同，entity_code 相同 → 必然撞名 */
    private IDataSourceProvider logProvider(String dsId) {
        Set<DataSourceCapability> capabilities = Set.of(DataSourceCapability.LOG_SEARCH);
        List<EntityMeta> entities = List.of(EntityMeta.builder()
                .dsId(dsId)
                .entityCode(ENTITY_LOGS)
                .entityName("日志")
                // 用 desc 区分两个源：工具描述由 entityDesc 生成，便于断言"保留的是哪一个"
                .entityDesc(dsId.endsWith("_a") ? "日志源A" : "日志源B")
                .capability(DataSourceCapability.LOG_SEARCH.key())
                .build());
        return (IDataSourceProvider) Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[]{IDataSourceProvider.class, ILogSearchProvider.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "dsId" -> dsId;
                    case "capabilities" -> capabilities;
                    case "listEntities" -> entities;
                    default -> defaultValue(method.getReturnType());
                });
    }

    private IDataSourceRepository repositoryAllowing(List<String> capabilities) {
        return (IDataSourceRepository) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{IDataSourceRepository.class},
                (proxy, method, args) -> "queryAllowedCapabilities".equals(method.getName())
                        ? capabilities : defaultValue(method.getReturnType()));
    }

    private static void inject(Object target, String field, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(field);
        f.setAccessible(true);
        f.set(target, value);
    }

    private static Object defaultValue(Class<?> type) {
        if (type == boolean.class) {
            return false;
        }
        if (type == int.class) {
            return 0;
        }
        if (type == long.class) {
            return 0L;
        }
        if (type == double.class) {
            return 0d;
        }
        return null;
    }

}
