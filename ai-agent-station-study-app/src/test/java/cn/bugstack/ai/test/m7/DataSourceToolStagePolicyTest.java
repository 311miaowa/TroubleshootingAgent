package cn.bugstack.ai.test.m7;

import cn.bugstack.ai.domain.agent.adapter.port.datasource.DataSourceCapability;
import cn.bugstack.ai.domain.agent.adapter.port.datasource.IDataSourceProvider;
import cn.bugstack.ai.domain.agent.adapter.port.datasource.IDataSourceRegistry;
import cn.bugstack.ai.domain.agent.adapter.port.datasource.ILogSearchProvider;
import cn.bugstack.ai.domain.agent.adapter.repository.IDataSourceRepository;
import cn.bugstack.ai.domain.agent.model.valobj.datasource.EntityMeta;
import cn.bugstack.ai.domain.agent.service.datasource.tool.DataSourceToolCallbackFactory;
import org.junit.Assert;
import org.junit.Test;
import org.springframework.ai.tool.ToolCallback;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * M7a 工具的**阶段裁剪**与 **schema 无调参口**验证（纯单测：不启动 Spring、不连数据库、不连 ES）
 * <p>
 * 为什么要有这一组：M7a 的验收判据里两条最容易"看着像做了其实没做"——
 * <ol>
 *     <li><b>执行阶段才有该工具</b>（分析/监督阶段必须拿不到）——决定四步链会不会被短路；</li>
 *     <li><b>模型没有调参口</b>（{@code k} / {@code minSample} / 桶粒度 / 分组维度都不在 schema 里）——
 *         否则模型会开始"多轮试参数"。</li>
 * </ol>
 * 这两条都是**装配期逻辑**，可以用假的注册表/策略仓储在单测里钉死，不必等真实环境。
 * <p>
 * 说明：工厂的依赖是 {@code @Resource} 私有字段，这里用反射注入 + 动态代理构造假实现，
 * 好处是**不依赖接口的完整方法集**（只回答用得到的那几个），接口演进时本测试不会碎。
 *
 * @author miaowazhongzi
 */
public class DataSourceToolStagePolicyTest {

    private static final String DS_ES = "ds-es";
    private static final String ENTITY_LOGS = "logs";

    /** 执行阶段：日志检索 + 现象归纳 + 实体字典 */
    @Test
    public void executorStage_getsLogSearchAndSummary() throws Exception {
        DataSourceToolCallbackFactory factory = factoryWith(List.of("log_search", "entity_meta", "anomaly_summary"));

        List<String> names = toolNames(factory);

        Assert.assertTrue("执行阶段应有原始检索：" + names, names.contains("query_logs"));
        Assert.assertTrue("执行阶段应有现象归纳：" + names, names.contains("query_log_summary"));
        Assert.assertTrue("执行阶段仍应有实体字典：" + names, names.contains("describe_entity"));
    }

    /** 分析 / 监督阶段：只给 entity_meta（这是"四步链不被短路"的关键证据） */
    @Test
    public void analyzerStage_onlyEntityMeta() throws Exception {
        DataSourceToolCallbackFactory factory = factoryWith(List.of("entity_meta"));

        List<String> names = toolNames(factory);

        Assert.assertEquals("分析阶段只应拿到 describe_entity：" + names, List.of("describe_entity"), names);
        Assert.assertFalse("分析阶段不得出现现象归纳工具", names.contains("query_log_summary"));
    }

    /** 反例（可摘除性）：把 anomaly_summary 的策略置 0 后，归纳工具消失，其余工具照常——证明本层是增量 */
    @Test
    public void summaryDenied_toolAbsentOthersUnchanged() throws Exception {
        DataSourceToolCallbackFactory factory = factoryWith(List.of("log_search", "entity_meta"));

        List<String> names = toolNames(factory);

        Assert.assertFalse("策略未授权时不得挂载归纳工具：" + names, names.contains("query_log_summary"));
        Assert.assertTrue("原始日志检索不受影响：" + names, names.contains("query_logs"));
        Assert.assertTrue("实体字典不受影响：" + names, names.contains("describe_entity"));
    }

    /** 没有日志源时，即使策略授权也不挂归纳工具（避免给模型一个永远返回告警的工具） */
    @Test
    public void noLogSource_noSummaryTool() throws Exception {
        DataSourceToolCallbackFactory factory = newFactory();
        inject(factory, "dataSourceRegistry", registryWith(List.of("entity_query")));
        inject(factory, "dataSourceRepository", repositoryWith(List.of("log_search", "entity_meta", "anomaly_summary")));

        List<String> names = toolNames(factory);

        Assert.assertFalse("无日志源时不应挂归纳工具：" + names, names.contains("query_log_summary"));
    }

    /** schema 里不得出现任何调参口（k / minSample / 桶粒度 / 分组维度） */
    @Test
    public void summarySchema_hasNoTuningKnobs() throws Exception {
        DataSourceToolCallbackFactory factory = factoryWith(List.of("log_search", "entity_meta", "anomaly_summary"));

        String schema = factory.buildFor("4103", "PRECISION_EXECUTOR_CLIENT").stream()
                .filter(c -> "query_log_summary".equals(c.getToolDefinition().name()))
                .map(c -> c.getToolDefinition().inputSchema())
                .findFirst()
                .orElseThrow(() -> new AssertionError("未找到 query_log_summary 工具"));

        Assert.assertTrue("schema 应含 keyword：" + schema, schema.contains("keyword"));
        Assert.assertTrue("schema 应含 limit：" + schema, schema.contains("limit"));
        Assert.assertFalse("schema 不得暴露 minSample：" + schema, schema.contains("minSample"));
        Assert.assertFalse("schema 不得暴露桶粒度：" + schema, schema.contains("bucketMinutes"));
        Assert.assertFalse("schema 不得暴露分组维度：" + schema, schema.contains("groupBy"));
        Assert.assertFalse("schema 不得暴露 IQR 系数：" + schema, schema.contains("\"k\""));
        Assert.assertFalse("schema 不得暴露数据源/索引/字段名：" + schema,
                schema.contains("dsId") || schema.contains("index") || schema.contains("bucket"));
    }

    // ------------------------------------------------------------------ P0-2：fail-closed（部署默认 = 拒绝）

    /**
     * 部署默认必须是"拒绝"：`@Value` 的兜底值是 {@code true}。
     * <p>
     * 纯单测构造的实例没有 Spring 注入，字段初始化刻意保留 {@code false} 以免动摇既有装配类测试，
     * 所以"部署默认"只能从注解上钉死（这是唯一能单测到的证据面）。
     */
    @Test
    public void failClosedAnnotationDefault_isDeny() throws Exception {
        java.lang.reflect.Field field =
                DataSourceToolCallbackFactory.class.getDeclaredField("failClosedOnUnknownStage");
        org.springframework.beans.factory.annotation.Value value =
                field.getAnnotation(org.springframework.beans.factory.annotation.Value.class);

        Assert.assertNotNull("开关字段必须由 @Value 绑定：", value);
        String expr = value.value();
        Assert.assertTrue("部署默认应为拒绝（兜底 true）：" + expr, expr.endsWith(":true}") || expr.endsWith(":true"));
    }

    /** 打开 fail-closed 开关（部署默认就是这个状态）：未知阶段必须拒绝（0 个语义工具） */
    @Test
    public void unknownStage_failClosedFlag_deniesAll() throws Exception {
        DataSourceToolCallbackFactory factory = factoryWith(List.of("log_search", "entity_meta", "anomaly_summary"));
        inject(factory, "failClosedOnUnknownStage", true);

        List<String> names = factory.buildFor("4102", null).stream()
                .map(c -> c.getToolDefinition().name()).collect(Collectors.toList());

        Assert.assertTrue("fail-closed 后未知阶段不得有任何工具：" + names, names.isEmpty());
    }

    /** 显式把开关关掉（fail-closed=false）：回到"只告警、放行"的旧行为（逃生口仍然可用） */
    @Test
    public void unknownStage_switchDisabled_stillOpens() throws Exception {
        DataSourceToolCallbackFactory factory = factoryWith(List.of("log_search", "entity_meta", "anomaly_summary"));
        inject(factory, "failClosedOnUnknownStage", false);

        List<String> names = factory.buildFor("4102", null).stream()
                .map(c -> c.getToolDefinition().name()).collect(Collectors.toList());

        Assert.assertFalse("开关关闭时应放行全部工具：" + names, names.isEmpty());
        Assert.assertTrue("应含归纳工具（有日志源且能力授权）：" + names, names.contains("query_log_summary"));
    }

    /** 策略查询抛异常：开关关闭时放行、开启时拒绝，且都不让装配失败 */
    @Test
    public void policyQueryFailure_respectsSwitch() throws Exception {
        IDataSourceRepository broken = (IDataSourceRepository) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{IDataSourceRepository.class},
                (proxy, method, args) -> {
                    if ("queryAllowedCapabilities".equals(method.getName())) {
                        throw new IllegalStateException("模拟策略表不可用");
                    }
                    return defaultValue(method.getReturnType());
                });

        DataSourceToolCallbackFactory openFactory = newFactory();
        inject(openFactory, "dataSourceRegistry", registryWith(List.of("log_search")));
        inject(openFactory, "dataSourceRepository", broken);
        inject(openFactory, "failClosedOnUnknownStage", false);
        List<String> open = toolNames(openFactory);
        Assert.assertFalse("开关关闭时异常应放行（不裁剪），不能是空集：" + open, open.isEmpty());

        DataSourceToolCallbackFactory closedFactory = newFactory();
        inject(closedFactory, "dataSourceRegistry", registryWith(List.of("log_search")));
        inject(closedFactory, "dataSourceRepository", broken);
        inject(closedFactory, "failClosedOnUnknownStage", true);
        List<String> closed = closedFactory.buildFor("4102", "PRECISION_EXECUTOR_CLIENT").stream()
                .map(c -> c.getToolDefinition().name()).collect(Collectors.toList());
        Assert.assertTrue("开关开启时异常应拒绝：" + closed, closed.isEmpty());
    }

    // ------------------------------------------------------------------ M8：故障窗工具

    /** fault_window 授权时，执行阶段应挂出 query_fault_baseline */
    @Test
    public void faultWindowAllowed_toolMounted() throws Exception {
        DataSourceToolCallbackFactory factory = factoryWith(List.of("log_search", "entity_meta", "fault_window"));

        List<String> names = toolNames(factory);

        Assert.assertTrue("应挂出故障窗对比工具：" + names, names.contains("query_fault_baseline"));
    }

    /** 未授权 fault_window 时不挂（其余工具不受影响） */
    @Test
    public void faultWindowDenied_toolAbsent() throws Exception {
        DataSourceToolCallbackFactory factory = factoryWith(List.of("log_search", "entity_meta"));

        List<String> names = toolNames(factory);

        Assert.assertFalse("未授权不应挂出故障窗工具：" + names, names.contains("query_fault_baseline"));
        Assert.assertTrue("原始检索不受影响：" + names, names.contains("query_logs"));
    }

    /** 故障窗工具的 schema 里**不得**出现任何时间参数或调参口（窗口来自 ToolContext，不进 schema） */
    @Test
    public void faultWindowSchema_hasNoTimeOrTuningParams() throws Exception {
        DataSourceToolCallbackFactory factory = factoryWith(List.of("log_search", "entity_meta", "fault_window"));

        String schema = factory.buildFor("4102", "PRECISION_EXECUTOR_CLIENT").stream()
                .filter(c -> "query_fault_baseline".equals(c.getToolDefinition().name()))
                .map(c -> c.getToolDefinition().inputSchema())
                .findFirst()
                .orElseThrow(() -> new AssertionError("未找到 query_fault_baseline 工具"));

        Assert.assertTrue("schema 应含 keyword：" + schema, schema.contains("keyword"));
        Assert.assertFalse("schema 不得出现 startTime：" + schema, schema.contains("startTime"));
        Assert.assertFalse("schema 不得出现 endTime：" + schema, schema.contains("endTime"));
        Assert.assertFalse("schema 不得出现 faultStart：" + schema, schema.contains("faultStart"));
        Assert.assertFalse("schema 不得出现 bucket：" + schema, schema.contains("bucket"));
        Assert.assertFalse("schema 不得出现 offset：" + schema, schema.contains("offset"));
    }

    // ------------------------------------------------------------------ 夹具

    private DataSourceToolCallbackFactory factoryWith(List<String> allowedCapabilities) throws Exception {
        DataSourceToolCallbackFactory factory = newFactory();
        inject(factory, "dataSourceRegistry", registryWith(List.of("log_search")));
        inject(factory, "dataSourceRepository", repositoryWith(allowedCapabilities));
        return factory;
    }

    private DataSourceToolCallbackFactory newFactory() {
        return new DataSourceToolCallbackFactory();
    }

    private List<String> toolNames(DataSourceToolCallbackFactory factory) {
        return factory.buildFor("4103", "PRECISION_EXECUTOR_CLIENT").stream()
                .map(c -> c.getToolDefinition().name())
                .collect(Collectors.toList());
    }

    /**
     * 假注册表：只回答 {@code listByTenant}（工厂装配时唯一用到的方法）
     */
    private IDataSourceRegistry registryWith(List<String> entityCapabilities) {
        IDataSourceProvider provider = providerWith(entityCapabilities);
        return (IDataSourceRegistry) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{IDataSourceRegistry.class},
                (proxy, method, args) -> "listByTenant".equals(method.getName())
                        ? List.of(provider) : defaultValue(method.getReturnType()));
    }

    /**
     * 假 Provider：同时实现 {@link ILogSearchProvider}（工厂用 {@code instanceof} 判断能否检索日志）
     */
    private IDataSourceProvider providerWith(List<String> entityCapabilities) {
        // 注意：数据库里能力串是小写（log_search），枚举常量是大写（LOG_SEARCH）
        Set<DataSourceCapability> capabilities = entityCapabilities.stream()
                .map(c -> DataSourceCapability.valueOf(c.toUpperCase()))
                .collect(Collectors.toSet());
        List<EntityMeta> entities = entityCapabilities.stream()
                .map(capability -> EntityMeta.builder()
                        .dsId(DS_ES)
                        .entityCode(ENTITY_LOGS)
                        .entityName("日志")
                        .entityDesc("业务日志")
                        .capability(capability)
                        .build())
                .collect(Collectors.toList());
        return (IDataSourceProvider) Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[]{IDataSourceProvider.class, ILogSearchProvider.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "dsId" -> DS_ES;
                    case "capabilities" -> capabilities;
                    case "listEntities" -> entities;
                    default -> defaultValue(method.getReturnType());
                });
    }

    /**
     * 假策略仓储：只回答 {@code queryAllowedCapabilities}（其余方法返回类型默认值）
     */
    private IDataSourceRepository repositoryWith(List<String> allowedCapabilities) {
        return (IDataSourceRepository) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{IDataSourceRepository.class},
                (proxy, method, args) -> "queryAllowedCapabilities".equals(method.getName())
                        ? allowedCapabilities : defaultValue(method.getReturnType()));
    }

    private Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) {
            return null;
        }
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

    private void inject(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    /** 保留：便于将来直接断言某个工具对象的执行结果（当前用不到） */
    @SuppressWarnings("unused")
    private ToolCallback find(List<ToolCallback> callbacks, String name) {
        return callbacks.stream().filter(c -> name.equals(c.getToolDefinition().name())).findFirst().orElse(null);
    }

}
