package cn.bugstack.ai.test.p1;

import cn.bugstack.ai.domain.agent.adapter.port.datasource.DataSourceCapability;
import cn.bugstack.ai.domain.agent.adapter.port.datasource.IDataSourceProvider;
import cn.bugstack.ai.domain.agent.adapter.port.datasource.IDataSourceRegistry;
import cn.bugstack.ai.domain.agent.adapter.port.datasource.IEntityQueryProvider;
import cn.bugstack.ai.domain.agent.adapter.port.datasource.ILogSearchProvider;
import cn.bugstack.ai.domain.agent.model.valobj.datasource.EntityMeta;
import cn.bugstack.ai.domain.agent.model.valobj.datasource.EntityQuery;
import cn.bugstack.ai.domain.agent.model.valobj.datasource.HealthStatus;
import cn.bugstack.ai.domain.agent.model.valobj.datasource.LogSearchQuery;
import cn.bugstack.ai.domain.agent.model.valobj.datasource.QueryResult;
import cn.bugstack.ai.domain.agent.model.valobj.AiClientVO;
import cn.bugstack.ai.domain.agent.service.datasource.tool.DataSourceToolCallbackFactory;
import org.junit.Assert;
import org.junit.Test;
import org.springframework.ai.tool.ToolCallback;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * P1 单测：钉住「模型可见工具面里不含任何 ES MCP 工具」这一不变量。
 * <p>
 * <b>为什么需要这个测试</b>：交接文档（{@code M4-datasource-health-design.md} §P1、
 * {@code M4-status-and-next-tasks.md} §4.2）把「ES MCP 暴露 6 个写/删类工具」列为待修问题。
 * 实测核查（{@code data/m4-evidence/es-mcp-exposure-verification.md}）结论是：
 * <ul>
 *     <li>ES MCP server <b>确实</b>暴露 11 个工具 = 读 5 + 写/删 6（已用 {@code tools/list} 实测）；</li>
 *     <li>库表 {@code ai_client_tool_mcp.status=1}、7 条 {@code tool_mcp} 绑定 {@code status=1}，
 *         后端启动时 MCP 进程确实被拉起并握手成功；</li>
 *     <li><b>但没有任何 MCP 工具到达模型</b>——运行时 9 个客户端的工具清单里 MCP 工具数为 0。</li>
 * </ul>
 * 即这是<b>潜在风险</b>（一旦有人把 {@code SyncMcpToolCallbackProvider} 接回 {@code AiClientNode}
 * 就会立刻全部暴露），而非现行暴露。
 * <p>
 * 原有覆盖只在 {@code cn.bugstack.ai.test.dao.DataSourceToolCallbackFactoryTest}
 * （{@code @SpringBootTest} 集成测试，被 surefire {@code test/dao/**} 排除，{@code mvn test} 不跑）。
 * 本测试是<b>无 Spring 上下文</b>的纯单测，默认 {@code mvn -o -B test} 就会执行，
 * 因此它是这道边界真正生效的回归网。
 * <p>
 * <b>边界（如实声明）</b>：本测试断言的是「工具工厂产出的工具面」，
 * 不是「对 {@code ChatClient} 的运行时全量拦截」。拦截面靠 {@code AiClientNode} 只消费
 * {@link DataSourceToolCallbackFactory#buildFor} 这一点保证，并由
 * {@link #mcpBeanNameList_hasNoConsumerInDeliveryPath()} 以反射方式钉住。
 *
 * @author miaowazhongzi
 */
public class EsMcpToolExposureTest {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(EsMcpToolExposureTest.class);

    /**
     * ES MCP server 实测暴露的全部 11 个工具名（读 5 + 写/删 6）。
     * 数据来源：{@code initialize} + {@code tools/list} 实测，见 es-mcp-exposure-verification.md §1。
     */
    private static final List<String> ES_MCP_TOOLS = List.of(
            // 读
            "list_indices", "get_mappings", "search", "elasticsearch_health", "get_index_template",
            // 写 / 删
            "create_index", "create_mapping", "bulk", "reindex", "create_index_template", "delete_index_template");

    /** 交接文档点名的 6 个写/删类工具（ES_MCP_TOOLS 的子集，单独列出以便失败信息更明确） */
    private static final List<String> ES_MCP_WRITE_DELETE_TOOLS = List.of(
            "create_index", "create_mapping", "bulk", "reindex", "create_index_template", "delete_index_template");

    /** 非 MCP 的普通写操作名（ES_MCP_TOOLS 之外的对照项） */
    private static final List<String> NON_MCP_WRITE_TOOLS = List.of("index_document", "delete_document");

    // ------------------------------------------------------------------ 核心不变量

    /**
     * 无阶段裁剪时（{@code clientType} 为空）：工具面 = 语义工具，且不含任何 MCP 工具。
     */
    @Test
    public void modelVisibleTools_containNoMcpTools_whenNoStagePruning() {
        DataSourceToolCallbackFactory factory = newFactory(
                new StubRegistry(List.of(logSearchProvider(), entityQueryProvider())));

        List<String> names = toolNames(factory, null);

        // 正向：语义工具在场（证明工具面确实非空、断言不是在空集上白过）
        Assert.assertTrue("应含 query_logs", names.contains("query_logs"));
        Assert.assertTrue("应含 describe_entity", names.contains("describe_entity"));
        Assert.assertTrue("应含 query_correlated_timeline", names.contains("query_correlated_timeline"));

        // 反向：一个 MCP 工具都不许出现
        assertNoMcpTools(names);
        // 工具面必须完全落在语义工具集合内（白名单式断言，比黑名单更难绕过）
        assertToolsAreSemanticOnly(names);
    }

    /**
     * 有阶段裁剪时（{@code clientType} 非空）：策略查询失败会兜底为"不裁剪"，
     * 工具面依然不得出现 MCP 工具。
     */
    @Test
    public void modelVisibleTools_containNoMcpTools_whenStagePruningIsOn() {
        DataSourceToolCallbackFactory factory = newFactory(
                new StubRegistry(List.of(logSearchProvider(), entityQueryProvider())));

        List<String> names = toolNames(factory, "PRECISION_EXECUTOR_CLIENT");

        assertNoMcpTools(names);
        assertToolsAreSemanticOnly(names);
    }

    /**
     * 注册表里只有 MCP 型数据源（无 log_search / entity_query 能力）时：
     * 既不该产生任何 MCP 工具，也不该挂跨源工具（因为"没有可查询的来源"）。
     * <p>
     * 这条同时是"测试有效"的对照：它证明 {@link #toolNames} 确实在读取工厂真实产出的工具名。
     */
    @Test
    public void mcpOnlyRegistry_yieldsNoToolsAtAll() {
        DataSourceToolCallbackFactory factory = newFactory(
                new StubRegistry(List.of(mcpOnlyProvider())));

        List<String> names = toolNames(factory, null);

        Assert.assertTrue("MCP 型来源不应产生任何 query_* 工具，实际=" + names,
                names.stream().noneMatch(n -> n.startsWith("query_")));
        Assert.assertFalse("无 log_search/entity_query 时不应挂跨源工具",
                names.contains("query_correlated_timeline"));
        assertNoMcpTools(names);
    }

    // ------------------------------------------------------------------ 谓词自检（防止测试本身失效）

    /**
     * 谓词自检：证明"识别 MCP 工具"的逻辑真的能命中 MCP 工具名，
     * 否则上面的断言可能只是"谓词永远返回 false"而白过。
     */
    @Test
    public void mcpToolPredicate_actuallyDetectsMcpToolNames() {
        for (String mcpTool : ES_MCP_TOOLS) {
            Assert.assertTrue("应识别为 MCP 工具: " + mcpTool, isMcpToolName(mcpTool));
        }
        Assert.assertEquals("ES MCP 实测工具数应为 11", 11, ES_MCP_TOOLS.size());
        Assert.assertEquals("其中写/删类应为 6 个", 6, ES_MCP_WRITE_DELETE_TOOLS.size());
        for (String writeTool : ES_MCP_WRITE_DELETE_TOOLS) {
            Assert.assertTrue("6 个写/删工具必须都在实测清单里: " + writeTool, ES_MCP_TOOLS.contains(writeTool));
            Assert.assertTrue("应识别为 MCP 工具: " + writeTool, isMcpToolName(writeTool));
        }

        // 对照：语义工具与普通写操作名不得被误判为 MCP 工具
        for (String semantic : List.of("query_logs", "query_orders", "query_payments",
                "describe_entity", "query_correlated_timeline")) {
            Assert.assertFalse("语义工具不应被判为 MCP 工具: " + semantic, isMcpToolName(semantic));
        }
        for (String other : NON_MCP_WRITE_TOOLS) {
            Assert.assertFalse("非 MCP 写操作不应被判为 MCP 工具: " + other, isMcpToolName(other));
        }
    }

    // ------------------------------------------------------------------ 交付路径反射检查

    /**
     * 反射验证：{@code AiClientVO.getMcpBeanNameList()} <b>在当前交付路径上没有调用方</b>。
     * <p>
     * 这是"MCP 工具到不了模型"的代码层原因：MCP 客户端只被 {@code AiClientToolMcpNode}
     * 注册成 {@code McpSyncClient} Bean，而工具注入路径（{@code AiClientNode}）只消费
     * {@link DataSourceToolCallbackFactory#buildFor}。
     * <p>
     * 本断言是<b>守护性</b>的：一旦有人把 MCP Bean 名接回工具注入路径，它会提醒复查本测试的结论。
     * 说明：反射看不到跨模块的源码调用点，它只钉住"该方法本身仍然只是产出 Bean 名、并非工具清单"。
     */
    @Test
    public void mcpBeanNameList_hasNoConsumerInDeliveryPath() throws Exception {
        AiClientVO vo = AiClientVO.builder()
                .clientId("4102")
                .mcpIdList(List.of("5007"))
                .advisorIdList(List.of())
                .build();

        List<String> beanNames = vo.getMcpBeanNameList();
        Assert.assertEquals("应产出 1 个 MCP Bean 名", 1, beanNames.size());
        Assert.assertEquals("Bean 名应为 ai_client_tool_mcp_5007", "ai_client_tool_mcp_5007", beanNames.get(0));

        // 该方法产出的必须是"Bean 名"，不能是任何模型可见的工具名
        for (String beanName : beanNames) {
            Assert.assertFalse("Bean 名不得是 MCP 工具名: " + beanName, isMcpToolName(beanName));
        }

        // 该方法只读 mcpIdList，不涉及任何工具回调
        Method m = AiClientVO.class.getMethod("getMcpBeanNameList");
        Assert.assertEquals("返回类型应为 List", List.class, m.getReturnType());
        for (Class<?> paramType : m.getParameterTypes()) {
            Assert.assertFalse("不应接收 ToolCallback 参数",
                    ToolCallback.class.isAssignableFrom(paramType));
        }
    }

    /**
     * 源码级验证：{@code AiClientNode} <b>不得引用 ES MCP(5007)</b>。
     * <p>
     * <b>为什么不是"禁止一切 MCP"</b>：飞书写类工具通道（见
     * {@code docs/dev-ops/P2-feishu-write-tool-channel-design.md}）**需要**在工具注入路径上
     * 引入 MCP 客户端。所以本断言的正确语义是"**禁止 ES MCP**"，而不是"禁止 MCP"——
     * 否则它会在实现飞书时变成假红灯，最终被人删掉，那才是真正的安全损失。
     * <p>
     * 实现要点：反射看不出"某个 Object/接口类型的字段运行时到底注入的是谁"，
     * 所以这里不只看字段类型，还看**字段名与类型名里是否出现 5007 / elasticsearch 线索**。
     * （实测过：把字段声明成 {@code Object mcpSyncClientProbe} 时，只看类型名的旧断言会静默放过。）
     */
    @Test
    public void clientNode_doesNotReferenceEsMcp() {
        Class<?> nodeClass;
        try {
            nodeClass = Class.forName("cn.bugstack.ai.domain.agent.service.armory.AiClientNode");
        } catch (ClassNotFoundException e) {
            throw new AssertionError("AiClientNode 未找到，反射检查失效", e);
        }

        // 禁止在工具注入路径上出现的 ES MCP 线索（mcp_id=5007 / ES MCP server）
        List<String> forbiddenMarkers = List.of("5007", "elasticsearch", "esmcp", "es_mcp");

        List<String> offenders = new ArrayList<>();
        for (Field f : nodeClass.getDeclaredFields()) {
            if (Modifier.isStatic(f.getModifiers())) {
                continue;
            }
            String haystack = (f.getName() + " " + f.getType().getName()).toLowerCase();
            for (String marker : forbiddenMarkers) {
                if (haystack.contains(marker)) {
                    offenders.add(f.getName() + " : " + f.getType().getName() + "（命中 " + marker + "）");
                    break;
                }
            }
        }

        Assert.assertTrue("AiClientNode 不得引用 ES MCP(5007)，实际=" + offenders, offenders.isEmpty());
    }

    // ------------------------------------------------------------------ 工具

    /** 用反射注入 registry（无 Spring 上下文；其余 @Resource 字段本测试不会触达） */
    private DataSourceToolCallbackFactory newFactory(IDataSourceRegistry registry) {
        DataSourceToolCallbackFactory factory = new DataSourceToolCallbackFactory();
        setField(factory, "dataSourceRegistry", registry);
        return factory;
    }

    private void setField(Object target, String fieldName, Object value) {
        try {
            Field f = target.getClass().getDeclaredField(fieldName);
            f.setAccessible(true);
            f.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("注入字段失败: " + fieldName, e);
        }
    }

    private List<String> toolNames(DataSourceToolCallbackFactory factory, String clientType) {
        List<String> names = factory.buildFor("test-client", clientType).stream()
                .map(c -> c.getToolDefinition().name())
                .collect(Collectors.toList());
        log.info("单测实测工具面 clientType={} 工具={} 其中 MCP 工具={}",
                clientType, names, names.stream().filter(this::isMcpToolName).collect(Collectors.toList()));
        return names;
    }

    /** 黑名单：任何 MCP 工具名都不得出现 */
    private void assertNoMcpTools(List<String> names) {
        for (String name : names) {
            Assert.assertFalse("模型不应看到 ES MCP 工具: " + name
                    + "（全部工具: " + names + "）", isMcpToolName(name));
        }
        for (String writeTool : ES_MCP_WRITE_DELETE_TOOLS) {
            Assert.assertFalse("模型不应看到 ES MCP 写/删工具: " + writeTool, names.contains(writeTool));
        }
    }

    /** 白名单：工具名必须完全落在语义工具命名规则内 */
    private void assertToolsAreSemanticOnly(List<String> names) {
        for (String name : names) {
            boolean semantic = "describe_entity".equals(name)
                    || "query_correlated_timeline".equals(name)
                    || name.startsWith("query_");
            Assert.assertTrue("工具名不属于语义工具命名规则: " + name + "（全部工具: " + names + "）", semantic);
        }
    }

    /**
     * 是否 ES MCP 工具名。
     * 用"整名匹配"而非子串匹配——避免 {@code query_logs} 之类被 {@code bulk} 等子串误伤
     * （运行时日志核查里就出现过 {@code elasticsearch}/{@code _search} 的子串误命中）。
     */
    private boolean isMcpToolName(String name) {
        if (name == null) {
            return false;
        }
        return ES_MCP_TOOLS.contains(name);
    }

    // ------------------------------------------------------------------ Stub

    private IDataSourceProvider logSearchProvider() {
        return new StubLogSearchProvider(List.of(EntityMeta.builder()
                .dsId("ds_log_es")
                .entityCode("logs")
                .entityName("应用日志")
                .entityDesc("应用日志")
                .capability("log_search")
                .build()));
    }

    private IDataSourceProvider entityQueryProvider() {
        return new StubEntityQueryProvider(List.of(EntityMeta.builder()
                .dsId("ds_order_mysql")
                .entityCode("orders")
                .entityName("订单")
                .entityDesc("订单")
                .capability("entity_query")
                .build()));
    }

    private IDataSourceProvider mcpOnlyProvider() {
        return new StubProvider("ds_es_mcp_5007", "elasticsearch-mcp",
                Set.of(), List.of());
    }

    private static class StubRegistry implements IDataSourceRegistry {

        private final List<IDataSourceProvider> providers;

        StubRegistry(List<IDataSourceProvider> providers) {
            this.providers = providers;
        }

        @Override
        public void register(IDataSourceProvider provider) {
            throw new UnsupportedOperationException("本单测不需要注册");
        }

        @Override
        public void unregister(String dsId) {
            throw new UnsupportedOperationException("本单测不需要注销");
        }

        @Override
        public Optional<IDataSourceProvider> get(String dsId) {
            return providers.stream().filter(p -> p.dsId().equals(dsId)).findFirst();
        }

        @Override
        public List<IDataSourceProvider> listByTenant(String tenantId) {
            return providers;
        }
    }

    private static class StubProvider implements IDataSourceProvider {
        private final String dsId;
        private final String dsType;
        private final Set<DataSourceCapability> capabilities;
        private final List<EntityMeta> entities;

        StubProvider(String dsId, String dsType, Set<DataSourceCapability> capabilities, List<EntityMeta> entities) {
            this.dsId = dsId;
            this.dsType = dsType;
            this.capabilities = capabilities;
            this.entities = entities;
        }

        @Override
        public String dsId() {
            return dsId;
        }

        @Override
        public String dsType() {
            return dsType;
        }

        @Override
        public Set<DataSourceCapability> capabilities() {
            return capabilities;
        }

        @Override
        public HealthStatus healthCheck() {
            return HealthStatus.up();
        }

        @Override
        public List<EntityMeta> listEntities() {
            return entities;
        }
    }

    /**
     * 日志检索型 Provider。
     * <p>
     * <b>必须真正 implements {@link ILogSearchProvider}</b>——工厂按
     * {@code provider instanceof ILogSearchProvider} 决定是否挂 {@code query_logs}。
     * 一开始只有 {@link IDataSourceProvider} 时，{@code query_logs} 会被静默漏掉，
     * 导致"不含 MCP 工具"的断言在<b>空集</b>上白过（已被本测试的正向断言抓住）。
     */
    private static class StubLogSearchProvider extends StubProvider implements ILogSearchProvider {

        StubLogSearchProvider(List<EntityMeta> entities) {
            super("ds_log_es", "elasticsearch", Set.of(DataSourceCapability.LOG_SEARCH), entities);
        }

        @Override
        public QueryResult search(LogSearchQuery query) {
            return QueryResult.empty("ds_log_es", "logs");
        }

        @Override
        public void close() {
            // 本单测无资源需释放
        }
    }

    /**
     * 实体查询型 Provider（同样必须真正 implements {@link IEntityQueryProvider}）。
     */
    private static class StubEntityQueryProvider extends StubProvider implements IEntityQueryProvider {

        StubEntityQueryProvider(List<EntityMeta> entities) {
            super("ds_order_mysql", "mysql", Set.of(DataSourceCapability.ENTITY_QUERY), entities);
        }

        @Override
        public QueryResult query(EntityQuery query) {
            return QueryResult.empty("ds_order_mysql", "orders");
        }

        @Override
        public void close() {
            // 本单测无资源需释放
        }
    }

}
