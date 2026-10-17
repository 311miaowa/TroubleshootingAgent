package cn.bugstack.ai.domain.agent.service.datasource.tool;

import cn.bugstack.ai.domain.agent.adapter.port.datasource.DataSourceCapability;
import cn.bugstack.ai.domain.agent.adapter.port.datasource.IDataSourceProvider;
import cn.bugstack.ai.domain.agent.adapter.port.datasource.IDataSourceRegistry;
import cn.bugstack.ai.domain.agent.adapter.port.datasource.IEntityQueryProvider;
import cn.bugstack.ai.domain.agent.adapter.port.datasource.ILogSearchProvider;
import cn.bugstack.ai.domain.agent.adapter.repository.IDataSourceRepository;
import cn.bugstack.ai.domain.agent.model.valobj.datasource.EntityMeta;
import cn.bugstack.ai.domain.agent.model.valobj.datasource.EntityQuery;
import cn.bugstack.ai.domain.agent.model.valobj.datasource.FaultWindow;
import cn.bugstack.ai.domain.agent.model.valobj.datasource.FieldFilter;
import cn.bugstack.ai.domain.agent.model.valobj.datasource.LogSearchQuery;
import cn.bugstack.ai.domain.agent.model.valobj.datasource.QueryResult;
import cn.bugstack.ai.domain.agent.model.valobj.datasource.TimeRange;
import cn.bugstack.ai.domain.agent.service.datasource.AnomalySummarizationService;
import cn.bugstack.ai.domain.agent.service.datasource.CrossSourceCorrelationService;
import cn.bugstack.ai.domain.agent.service.datasource.DataSourceQueryService;
import cn.bugstack.ai.domain.agent.service.datasource.FaultWindowComparisonService;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import jakarta.annotation.Resource;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * 数据源语义工具工厂
 * <p>
 * 把「数据源 + 实体」转成模型可见的语义 {@link ToolCallback}：
 * <ul>
 *     <li>{@code query_<entityCode>} —— 日志检索 / 实体查询（只读，按 {@code entity_code} 生成）；</li>
 *     <li>{@code describe_entity} —— 列出有哪些实体、字段，补回"不再暴露原始 ES 工具"后失去的探索能力；</li>
 *     <li>{@code query_correlated_timeline} —— P1 跨源时间轴对齐（只在至少一侧可查时挂出）；</li>
 *     <li>{@code query_log_summary} —— M7a 现象归纳（只在有日志源时挂出）；</li>
 *     <li>{@code query_fault_baseline} —— M8 故障期/基线对比（故障窗经 {@code ToolContext} 注入，不进 schema）。</li>
 * </ul>
 * <p>
 * <b>模型永远看不到原始 MCP 工具</b>（写/删类工具在类型层面就不存在），也看不到 tenantId。
 * <p>
 * <b>M1.2 阶段裁剪</b>：按 {@code clientType} 查 {@code ai_stage_tool_policy} 得到允许的能力白名单，
 * 分析/监督阶段只给 {@code entity_meta}（describe_entity），执行阶段才给 {@code log_search}/
 * {@code entity_query}（query_*）。这修复"四步链被短路"。
 * <p>
 * <b>fail-closed 收敛（GAP §3 P0-2 · D-3 拍板）</b>：未知阶段（{@code clientType} 为空）与"策略查询异常"
 * **默认拒绝**（该阶段 0 个语义工具）并打 WARN；临时回退用 {@code xfg.datasource.policy.fail-closed=false}。
 * <p>
 * <b>边界</b>：schema 暂为「keyword + 时间窗 + limit」的固定形态（与 query_template
 * 实际消费的占位符一致，不暴露未接线的过滤参数）；字段字典驱动的 schema 生成留待后续。
 *
 * @author miaowazhongzi
 * 2025/6/27 18:29
 */
@Slf4j
@Component
public class DataSourceToolCallbackFactory {

    /*
     * P2 收敛：能力串一律从 DataSourceCapability.key() 派生 ——
     * 此前这里有第二份手写清单（"cross_source"/"fault_window"/"anomaly_summary"），
     * 与枚举各写一遍，新增能力时容易只改一处。
     */
    private static final String LOG_SEARCH = DataSourceCapability.LOG_SEARCH.key();
    private static final String ENTITY_QUERY = DataSourceCapability.ENTITY_QUERY.key();
    private static final String ENTITY_META = DataSourceCapability.ENTITY_META.key();

    /**
     * P1 跨源关联能力（工具名 {@value #CROSS_SOURCE_TOOL}）
     */
    private static final String CROSS_SOURCE = DataSourceCapability.CROSS_SOURCE.key();

    private static final String CROSS_SOURCE_TOOL = "query_correlated_timeline";

    /**
     * M7a 现象归纳能力（工具名 {@value #ANOMALY_SUMMARY_TOOL}）
     */
    private static final String ANOMALY_SUMMARY = DataSourceCapability.ANOMALY_SUMMARY.key();

    private static final String ANOMALY_SUMMARY_TOOL = "query_log_summary";

    /**
     * M8 故障期/基线对比能力（工具名 {@value #FAULT_WINDOW_TOOL}）。
     * <p>
     * 故障窗由请求侧提供、经 {@code ToolContext} 注入，**不进 schema**（与 {@code tenantId} 同一条纪律）。
     */
    private static final String FAULT_WINDOW = DataSourceCapability.FAULT_WINDOW.key();

    private static final String FAULT_WINDOW_TOOL = "query_fault_baseline";

    @Resource
    private IDataSourceRegistry dataSourceRegistry;

    @Resource
    private DataSourceQueryService dataSourceQueryService;

    @Resource
    private CrossSourceCorrelationService crossSourceCorrelationService;

    @Resource
    private AnomalySummarizationService anomalySummarizationService;

    @Resource
    private FaultWindowComparisonService faultWindowComparisonService;

    @Resource
    private IDataSourceRepository dataSourceRepository;

    /**
     * 阶段策略的 <b>fail-closed 开关</b>（GAP §3 P0-2 / D-3 拍板：先告警一个版本再拒 → **本版本已翻默认**）。
     * <p>
     * <b>部署默认 = 拒绝</b>：{@code @Value} 的兜底值与 {@code application-dev.yml} 都是 {@code true}，
     * 未知阶段（{@code clientType} 为空）与"策略查询异常"一律返回空集，并打一条带**补救 SQL** 的 WARN。
     * <p>
     * <b>字段初始化刻意保持 {@code false}</b>：纯单测直接 {@code new} 出来的实例没有 Spring 注入，
     * 保持"放行"才能让既有的一批装配类测试（依赖"无策略行 = 全量工具"）一例不改；
     * 部署时的真实默认由 {@code @Value}/yml 决定（见 {@code failClosedAnnotationDefault_isDeny} 用例钉死）。
     * <p>
     * 临时回退：把 {@code xfg.datasource.policy.fail-closed} 置 {@code false}（或环境变量
     * {@code XFG_DATASOURCE_POLICY_FAIL_CLOSED=false}），即回到"只告警、放行（不裁剪）"的旧行为。
     */
    @Value("${xfg.datasource.policy.fail-closed:true}")
    private boolean failClosedOnUnknownStage = false;

    /**
     * 为指定客户端构建语义工具（M1.2 起按阶段裁剪）
     *
     * @param clientId   客户端ID
     * @param clientType 阶段编码（等于 ai_agent_flow_config.client_type）；
     *                   为 null/空时按 {@code xfg.datasource.policy.fail-closed} 处理 ——
     *                   **默认拒绝（返回空集）**，只有显式把开关关掉才不裁剪（返回全部语义工具）
     */
    public List<ToolCallback> buildFor(String clientId, String clientType) {
        Set<String> allowed = resolveAllowedCapabilities(clientType);

        List<ToolCallback> callbacks = new ArrayList<>();

        // P2：装配前把 Provider 按 dsId 排序 —— 注册表底层是 ConcurrentHashMap，迭代顺序不保证；
        // 排序让"多源同名工具时保留哪一个"成为稳定、可复现的结果（与 CrossSourceCorrelationService 同一纪律）。
        List<IDataSourceProvider> providers = new ArrayList<>(dataSourceRegistry.listByTenant(null));
        providers.sort(Comparator.comparing(IDataSourceProvider::dsId, Comparator.nullsLast(String::compareTo)));

        for (IDataSourceProvider provider : providers) {
            List<EntityMeta> entities = provider.listEntities();
            if (entities == null) {
                continue;
            }
            for (EntityMeta entity : entities) {
                if (LOG_SEARCH.equals(entity.getCapability()) && provider instanceof ILogSearchProvider) {
                    if (isAllowed(allowed, LOG_SEARCH)) {
                        callbacks.add(buildLogSearchTool(provider.dsId(), entity));
                    }
                } else if (ENTITY_QUERY.equals(entity.getCapability()) && provider instanceof IEntityQueryProvider) {
                    // M1.3 接入 MysqlOrderProvider 后自然生效；当前无 entity_query Provider，不会触发
                    if (isAllowed(allowed, ENTITY_QUERY)) {
                        callbacks.add(buildEntityQueryTool(provider.dsId(), entity));
                    }
                }
            }
        }

        if (isAllowed(allowed, ENTITY_META)) {
            callbacks.add(buildDescribeEntityTool());
        }

        // P1：跨源对齐工具。只在"至少有一侧可查"时才挂出来，避免在空环境里给模型一个永远用不上的工具
        if (isAllowed(allowed, CROSS_SOURCE) && hasAnyQueryableSource()) {
            callbacks.add(buildCrossSourceTool());
        }

        // M7a：现象归纳工具。只在真有日志源时挂出（否则模型会拿到一个永远返回告警的工具）
        if (isAllowed(allowed, ANOMALY_SUMMARY) && firstLogDsId() != null) {
            callbacks.add(buildAnomalySummaryTool());
        }

        // M8：故障期/基线对比工具。同样只在真有日志源时挂出；窗口由 ToolContext 注入
        if (isAllowed(allowed, FAULT_WINDOW) && firstLogDsId() != null) {
            callbacks.add(buildFaultBaselineTool());
        }

        // P2：工具重名检测。多数据源若暴露了同名 entity_code，会拼出两个 query_xxx：
        // Spring AI 的工具表按名字索引，重名会让"模型调的是哪一个"变得不可预期，
        // 因此保留首个、丢弃后续，并 WARN 指明是哪些名字（注册表侧才是该改的地方）。
        // 落地顺序由上面的 dsId 排序保证确定性：保留的一律是 dsId 字典序最小的那个源。
        List<ToolCallback> unique = new ArrayList<>(callbacks.size());
        Set<String> seenNames = new HashSet<>();
        for (ToolCallback callback : callbacks) {
            String toolName = callback.getToolDefinition().name();
            if (seenNames.add(toolName)) {
                unique.add(callback);
            } else {
                log.warn("工具名重复，已保留 dsId 字典序最小的那个源：name={}（多个数据源暴露了同名 entity_code，请检查 ai_data_source/实体配置）",
                        toolName);
            }
        }

        log.info("构建语义工具 clientId={} clientType={} 允许能力={} 工具={}",
                clientId, clientType, allowed == null ? "[全部]" : allowed,
                unique.stream().map(c -> c.getToolDefinition().name()).toList());

        return unique;
    }

    /**
     * 解析阶段允许的能力白名单（GAP §3 P0-2 / D-3 口径：**默认拒绝**，可用开关回退）。
     * <p>
     * 四种情况：
     * <ol>
     *     <li><b>正常</b>：查 {@code ai_stage_tool_policy} 得到 allowed=1 的能力集合；</li>
     *     <li><b>有阶段但策略表没行</b>：返回**空集**（本来就是拒绝，不在本次改动范围）；</li>
     *     <li><b>未知阶段</b>（{@code clientType} 为空）：按 {@code xfg.datasource.policy.fail-closed} 决定 ——
     *         默认 {@code true} 即**拒绝**（返回空集），置 {@code false} 才回到"告警放行"；</li>
     *     <li><b>策略查询异常</b>：同上，按开关决定"告警放行"还是"拒绝"（决不让装配失败）。</li>
     * </ol>
     */
    private Set<String> resolveAllowedCapabilities(String clientType) {
        if (clientType == null || clientType.isBlank()) {
            return failOpenOrDeny(clientType, "未知阶段（clientType 为空）");
        }
        try {
            List<String> capabilities = dataSourceRepository.queryAllowedCapabilities(clientType);
            if (capabilities == null || capabilities.isEmpty()) {
                return new HashSet<>();
            }
            return new HashSet<>(capabilities);
        } catch (Exception e) {
            return failOpenOrDeny(clientType, "策略查询异常：" + e.getMessage());
        }
    }

    /**
     * fail-open / fail-closed 分流：
     * <ul>
     *     <li>开关开（**部署默认**）→ 返回**空集**（该阶段没有语义工具），并 WARN 说明后果；</li>
     *     <li>开关关（临时回退，非默认）→ 返回 {@code null}（不裁剪）并 WARN，**附补救 SQL 与排查提示**。</li>
     * </ul>
     */
    private Set<String> failOpenOrDeny(String clientType, String reason) {
        String remedy = "补救 SQL：INSERT INTO ai_stage_tool_policy (stage_code, capability, allowed) "
                + "VALUES ('<阶段编码>', '<能力>', 1);"
                + " 并核对调用方是否漏传 clientType（见 docs/dev-ops/UPGRADE-hot-reload-deepseek.md）";
        if (failClosedOnUnknownStage) {
            log.warn("阶段策略 fail-closed 已开启：{} clientType={} → **拒绝全部语义工具**"
                    + "（该阶段只剩纯文本推理）。{}", reason, clientType, remedy);
            return new HashSet<>();
        }
        log.warn("阶段策略 fail-open（fail-closed 已被显式关闭：告警放行，不是默认行为）：{} clientType={} → 不裁剪、返回全部语义工具。{}",
                reason, clientType, remedy);
        return null;
    }

    private boolean isAllowed(Set<String> allowed, String capability) {
        return allowed == null || allowed.contains(capability);
    }

    private ToolCallback buildLogSearchTool(String dsId, EntityMeta entity) {
        String toolName = "query_" + entity.getEntityCode();
        String description = buildQueryToolDescription(entity);
        String schema = LOG_SEARCH_SCHEMA;

        return tool(toolName, dsId, entity.getEntityCode(), description, schema, toolInput ->
                dataSourceQueryService.search(dsId, parseLogSearchQuery(toolInput)));
    }

    private ToolCallback buildEntityQueryTool(String dsId, EntityMeta entity) {
        String toolName = "query_" + entity.getEntityCode();
        String description = buildQueryToolDescription(entity);
        // P1-2：实体模板里带 ${filters} 才暴露"通用过滤"入参，且可选字段来自该实体的字段字典
        // （is_filterable=1）——字典驱动 schema 的第一步，同时保证未启用过滤的实体行为完全不变
        String schema = supportsFilters(dsId, entity.getEntityCode())
                ? buildEntityQuerySchemaWithFilters(entity)
                : ENTITY_QUERY_SCHEMA;

        return tool(toolName, dsId, entity.getEntityCode(), description, schema, toolInput ->
                dataSourceQueryService.query(dsId, parseEntityQuery(toolInput, entity)));
    }

    /**
     * 该实体的 query_template 是否启用了 {@code ${filters}}（只有启用才允许按字段过滤）
     */
    private boolean supportsFilters(String dsId, String entityCode) {
        try {
            List<IDataSourceRepository.EntityConfig> configs = dataSourceRepository.queryEnabledEntityConfigs(dsId);
            if (configs == null) {
                return false;
            }
            return configs.stream()
                    .filter(c -> entityCode.equals(c.getEntityCode()))
                    .anyMatch(c -> c.getQueryTemplate() != null
                            && c.getQueryTemplate().contains(EntityQuery.FILTERS_PLACEHOLDER));
        } catch (Exception e) {
            log.warn("查询实体模板失败 dsId={} entityCode={}，按不支持过滤处理：{}", dsId, entityCode, e.getMessage());
            return false;
        }
    }

    /**
     * 生成带 {@code filters} 的实体查询 schema：可选字段由字段字典（is_filterable=1）决定
     * <p>
     * 用 {@code enum} 把白名单直接告诉模型——它不会去猜字段名，也没机会拼出字典外的字段。
     */
    private String buildEntityQuerySchemaWithFilters(EntityMeta entity) {
        List<String> filterable = new ArrayList<>();
        if (entity.getFields() != null) {
            for (EntityMeta.FieldMeta field : entity.getFields()) {
                if (field.isFilterable() && field.getFieldName() != null) {
                    filterable.add(field.getFieldName());
                }
            }
        }

        JSONObject filterItem = new JSONObject();
        filterItem.put("type", "object");
        JSONObject properties = new JSONObject();
        JSONObject fieldProperty = new JSONObject();
        fieldProperty.put("type", "string");
        fieldProperty.put("enum", filterable);
        fieldProperty.put("description", "过滤字段（必须来自该枚举）");
        properties.put("field", fieldProperty);
        JSONObject operatorProperty = new JSONObject();
        operatorProperty.put("type", "string");
        operatorProperty.put("enum", List.of("EQ", "NE", "GT", "GTE", "LT", "LTE", "LIKE", "IN"));
        operatorProperty.put("description", "操作符，默认 EQ；IN 时 value 用英文逗号分隔多个值");
        properties.put("operator", operatorProperty);
        JSONObject valueProperty = new JSONObject();
        valueProperty.put("type", "string");
        valueProperty.put("description", "比较值（字符串形式，类型转换由后端按字段字典完成）");
        properties.put("value", valueProperty);
        filterItem.put("properties", properties);
        filterItem.put("required", List.of("field", "value"));

        JSONObject filtersProperty = new JSONObject();
        filtersProperty.put("type", "array");
        filtersProperty.put("description", "可选的结构化过滤条件（多个条件之间是 AND）");
        filtersProperty.put("items", filterItem);

        JSONObject schemaProperties = new JSONObject();
        JSONObject startTime = new JSONObject();
        startTime.put("type", "string");
        startTime.put("description", "起始时间，ISO-8601，可选，默认最近1小时");
        schemaProperties.put("startTime", startTime);
        JSONObject endTime = new JSONObject();
        endTime.put("type", "string");
        endTime.put("description", "结束时间，ISO-8601，可选，默认现在");
        schemaProperties.put("endTime", endTime);
        JSONObject orderNo = new JSONObject();
        orderNo.put("type", "string");
        orderNo.put("description", "订单号精确过滤，可选");
        schemaProperties.put("orderNo", orderNo);
        schemaProperties.put("filters", filtersProperty);
        JSONObject limit = new JSONObject();
        limit.put("type", "integer");
        limit.put("description", "返回条数，默认20，上限200");
        schemaProperties.put("limit", limit);

        JSONObject schema = new JSONObject();
        schema.put("type", "object");
        schema.put("properties", schemaProperties);
        return schema.toJSONString();
    }

    private ToolCallback buildDescribeEntityTool() {
        return tool("describe_entity", null, null,
                "列出当前可查询的数据实体及其字段字典，用于了解有哪些数据、字段含义。",
                DESCRIBE_ENTITY_SCHEMA, toolInput -> {
                    List<EntityMeta> all = new ArrayList<>();
                    for (IDataSourceProvider provider : dataSourceRegistry.listByTenant(null)) {
                        List<EntityMeta> entities = provider.listEntities();
                        if (entities != null) {
                            all.addAll(entities);
                        }
                    }
                    return all;
                });
    }

    /**
     * P1 跨源对齐工具：一次调用同时查日志侧与订单侧，按时间桶对齐返回。
     * <p>
     * 关键说明写进 description：<b>只做机械对齐、不做因果判断</b>，并且明确告诉模型"某一侧不可用时结果里会有告警"，
     * 避免它把"某侧 0 条"直接当成"无关联"。
     */
    private ToolCallback buildCrossSourceTool() {
        String description = "把「日志时间线」与「实体时间线」对齐到同一根时间轴，用于判断两个事件在时间上是否吻合"
                + "（例如：下单失败与限流告警是否发生在同一分钟）。"
                + "一次调用内部会分别检索日志与查询实体（默认订单；可用 entityCode 指定其它实体，如 payments 支付流水），"
                + "并按时间桶返回：bucketStart（桶起点，Asia/Shanghai）、"
                + "logCount/orderCount（桶内两侧条数）、logSummary/orderSummary（桶内两侧摘要）。"
                + "本工具只做机械对齐，不做因果推断；若某一侧不可用或 0 命中，结果里会带 warnings 说明，"
                + "请勿把「某侧 0 条」直接理解为「无关联」。";
        return tool(CROSS_SOURCE_TOOL, CrossSourceCorrelationService.CORRELATE_DS_ID,
                CrossSourceCorrelationService.CORRELATE_ENTITY_CODE, description, CROSS_SOURCE_SCHEMA,
                toolInput -> {
                    JSONObject in = JSON.parseObject(toolInput == null || toolInput.isBlank() ? "{}" : toolInput);
                    int limit = in.getIntValue("limit");
                    return crossSourceCorrelationService.correlate(
                            in.getString("keyword"),
                            parseTimeRange(in),
                            in.getIntValue("bucketMinutes"),
                            limit > 0 ? limit : 0,
                            in.getString("entityCode"));
                });
    }

    /**
     * M7a 现象归纳工具：把原始日志行归纳成「一行 = 一个现象」的统计摘要。
     * <p>
     * description 里把<b>诚实边界</b>写清楚（无正常期基线 / 小样本不标离群 / 桶数不足不判离群），
     * 并要求模型把 {@code warnings} 纳入结论依据——避免它把"计数不高"直接读成"一切正常"。
     */
    private ToolCallback buildAnomalySummaryTool() {
        String description = "按关键字检索日志并返回**归一后的现象统计**：每个现象一行，含 pattern（模板）、"
                + "level、count（命中条数）、share（占窗口内总命中的比例）、firstSeen/lastSeen、"
                + "peakBucket/peakCount（命中最多的时间桶及其条数）、outlier（该峰值桶是否离群）。"
                + "统计口径由后端固定：模板归一 + " + AnomalySummarizationService.BUCKET_MINUTES
                + " 分钟时间桶 + 中位数与 IQR 离群判定，不接受调参。"
                + "**诚实边界**：不提供正常期基线，只能说明窗口内的绝对计数与占比，不能说「比平时更高」；"
                + "命中少于 3 条的现象不标离群；有效桶不足 5 个时不做离群判定。"
                + "返回里的 warnings 必须纳入你的结论依据。";
        return tool(ANOMALY_SUMMARY_TOOL, firstLogDsId(), AnomalySummarizationService.SUMMARY_ENTITY_CODE,
                description, ANOMALY_SUMMARY_SCHEMA, toolInput -> {
                    JSONObject in = JSON.parseObject(toolInput == null || toolInput.isBlank() ? "{}" : toolInput);
                    int limit = in.getIntValue("limit");
                    return anomalySummarizationService.summarize(in.getString("keyword"), parseTimeRange(in), limit);
                });
    }

    /**
     * M8 故障期/基线对比工具：故障窗来自 {@code ToolContext}，**工具本身不接受任何时间参数**。
     */
    private ToolCallback buildFaultBaselineTool() {
        String description = "把「故障期」与「对称正常基线」放在一起对比：返回按 phase 标注的时间桶计数与每分钟速率"
                + "（phase = fault / baseline_before / baseline_after），用于判断某现象在故障期是否明显高于正常期。"
                + "故障窗由本次请求提供，本工具**不接受时间参数**、也不接受调参。"
                + "**诚实边界**：只做机械对比、不判断因果；缺上次/下次故障时间等降级情况会写在 warnings 里"
                + "（例如「前基线按故障窗长度镜像构造」）；某段取不到数时给 warnings，不会编造数据。";
        return toolWithContext(FAULT_WINDOW_TOOL, firstLogDsId(), FaultWindowComparisonService.SUMMARY_ENTITY_CODE,
                description, FAULT_WINDOW_SCHEMA, (toolInput, context) -> {
                    JSONObject in = JSON.parseObject(toolInput == null || toolInput.isBlank() ? "{}" : toolInput);
                    FaultWindow window = context == null ? null : (FaultWindow) context.get(FaultWindow.CONTEXT_KEY);
                    return faultWindowComparisonService.compare(in.getString("keyword"), window, Instant.now(),
                            in.getIntValue("limit"));
                });
    }

    /**
     * 第一个日志源的 dsId（没有则 null）——用于"是否挂归纳工具"的判断，以及工具事件里的 dsId
     */
    private String firstLogDsId() {
        for (IDataSourceProvider provider : dataSourceRegistry.listByTenant(null)) {
            Set<DataSourceCapability> capabilities = provider.capabilities();
            if (capabilities != null && capabilities.contains(DataSourceCapability.LOG_SEARCH)) {
                return provider.dsId();
            }
        }
        return null;
    }

    /**
     * 是否存在可查询的来源（log_search 或 entity_query 任一）——决定要不要挂跨源对齐工具
     */
    private boolean hasAnyQueryableSource() {
        for (IDataSourceProvider provider : dataSourceRegistry.listByTenant(null)) {
            Set<DataSourceCapability> capabilities = provider.capabilities();
            if (capabilities == null) {
                continue;
            }
            if (capabilities.contains(DataSourceCapability.LOG_SEARCH)
                    || capabilities.contains(DataSourceCapability.ENTITY_QUERY)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 通用 ToolCallback 构造：定义与执行解耦。
     * <p>
     * M3.1 起重写双参 {@link ToolCallback#call(String, ToolContext)}：Spring AI 的
     * {@code DefaultToolCallingManager} 实际调用的是这个重载，我们从 {@code toolContext}
     * 里取出 {@link ToolCallEventSink}，在执行前后各发一条 start / result 结构化事件。
     * 单参 {@code call(String)} 保留（无 sink 时静默，兼容既有单测）。
     */
    private ToolCallback tool(String name, String dsId, String entityCode,
                              String description, String inputSchema, Function<String, Object> executor) {
        return toolWithContext(name, dsId, entityCode, description, inputSchema,
                (toolInput, context) -> executor.apply(toolInput));
    }

    /**
     * 带上下文的 ToolCallback 构造（M8）：执行器除 {@code toolInput} 外还能读到 {@link ToolContext} 的 map。
     * <p>
     * 故障窗（{@link FaultWindow}）就走这条通路——**不进 schema**，与 {@code tenantId} 同一条纪律。
     */
    private ToolCallback toolWithContext(String name, String dsId, String entityCode,
                                         String description, String inputSchema,
                                         BiFunction<String, Map<String, Object>, Object> executor) {
        return new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return ToolDefinition.builder()
                        .name(name)
                        .description(description)
                        .inputSchema(inputSchema)
                        .build();
            }

            @Override
            public String call(String toolInput) {
                return invoke(name, dsId, entityCode, null, toolInput, null, executor);
            }

            @Override
            public String call(String toolInput, ToolContext toolContext) {
                Map<String, Object> context = toolContext == null ? null : toolContext.getContext();
                ToolCallEventSink sink = context == null ? null
                        : (ToolCallEventSink) context.get(ToolCallEventSink.TOOL_CONTEXT_KEY);
                return invoke(name, dsId, entityCode, sink, toolInput, context, executor);
            }
        };
    }

    /**
     * 执行语义工具，并在有 sink 时实时发出 start / result 事件。
     * <p>
     * M3.2 纪律：事件版（{@code fullJson}）含 rawRef/行级 {@code _rawRef}，给证据面板；
     * 模型版对 {@link QueryResult} 用 {@link QueryResult#toModelJson()} 剥离 rawRef，其余类型原样。
     */
    private String invoke(String toolName, String dsId, String entityCode, ToolCallEventSink sink,
                          String toolInput, Map<String, Object> context,
                          BiFunction<String, Map<String, Object>, Object> executor) {
        if (sink != null) {
            sink.start(toolName, dsId, entityCode, toolInput);
        }
        Object resultObj;
        try {
            resultObj = executor.apply(toolInput, context);
        } catch (Exception e) {
            log.error("语义工具执行失败 name={}：{}", toolName, e.getMessage(), e);
            QueryResult err = QueryResult.empty(dsId, entityCode);
            err.getWarnings().add("工具执行失败: " + e.getMessage());
            resultObj = err;
        }
        String fullJson = JSON.toJSONString(resultObj);
        if (sink != null) {
            sink.result(toolName, dsId, entityCode, toolInput, fullJson);
        }
        return (resultObj instanceof QueryResult qr) ? qr.toModelJson() : fullJson;
    }

    private String buildQueryToolDescription(EntityMeta entity) {
        StringBuilder sb = new StringBuilder(entity.getEntityDesc() == null ? "" : entity.getEntityDesc());
        if (entity.getFields() != null && !entity.getFields().isEmpty()) {
            sb.append(" 可用字段：");
            for (EntityMeta.FieldMeta field : entity.getFields()) {
                sb.append(field.getFieldName());
                if (field.getFieldLabel() != null) {
                    sb.append("(").append(field.getFieldLabel()).append(")");
                }
                sb.append("、");
            }
        }
        return sb.toString();
    }

    private LogSearchQuery parseLogSearchQuery(String toolInput) {
        JSONObject in = JSON.parseObject(toolInput == null || toolInput.isBlank() ? "{}" : toolInput);
        int limit = in.getIntValue("limit");
        return LogSearchQuery.builder()
                .keyword(in.getString("keyword"))
                .limit(limit > 0 ? limit : 20)
                .timeRange(parseTimeRange(in))
                .build();
    }

    private EntityQuery parseEntityQuery(String toolInput, EntityMeta entity) {
        JSONObject in = JSON.parseObject(toolInput == null || toolInput.isBlank() ? "{}" : toolInput);
        int limit = in.getIntValue("limit");
        String orderNo = in.getString("orderNo");
        return EntityQuery.builder()
                .entityCode(entity.getEntityCode())
                .orderNo(orderNo == null || orderNo.isBlank() ? null : orderNo)
                .filters(parseFilters(in))
                .limit(limit > 0 ? limit : 20)
                .timeRange(parseTimeRange(in))
                .build();
    }

    /**
     * 解析通用过滤（P1-2）：只接受 {field, operator, value} 结构，操作符走枚举白名单。
     * <p>
     * 这里不做"字段是否可过滤"的判断——那是 Provider/SqlTemplateRenderer 按字段字典做的（唯一权威），
     * 本层只负责把结构翻译成 {@link FieldFilter}，避免出现两处不一致的白名单。
     */
    private List<FieldFilter> parseFilters(JSONObject in) {
        List<FieldFilter> filters = new ArrayList<>();
        com.alibaba.fastjson.JSONArray array = in.getJSONArray("filters");
        if (array == null || array.isEmpty()) {
            return filters;
        }
        for (int i = 0; i < array.size(); i++) {
            JSONObject item = array.getJSONObject(i);
            if (item == null) {
                continue;
            }
            String field = item.getString("field");
            String value = item.getString("value");
            if (field == null || field.isBlank() || value == null) {
                log.warn("忽略无效过滤条件（缺 field/value）：{}", item);
                continue;
            }
            FieldFilter.Operator operator = FieldFilter.Operator.EQ;
            String operatorText = item.getString("operator");
            if (operatorText != null && !operatorText.isBlank()) {
                try {
                    operator = FieldFilter.Operator.valueOf(operatorText.trim().toUpperCase());
                } catch (IllegalArgumentException e) {
                    log.warn("忽略不支持的过滤操作符：{}（field={}）", operatorText, field);
                    continue;
                }
            }
            filters.add(FieldFilter.builder().field(field).operator(operator).value(value).build());
        }
        return filters;
    }

    private TimeRange parseTimeRange(JSONObject in) {
        String start = in.getString("startTime");
        String end = in.getString("endTime");
        if (start == null || start.isBlank() || end == null || end.isBlank()) {
            return TimeRange.defaultRange();
        }
        try {
            return TimeRange.of(Instant.parse(start), Instant.parse(end));
        } catch (Exception e) {
            log.warn("时间窗解析失败，回退默认窗 start={} end={}：{}", start, end, e.getMessage());
            return TimeRange.defaultRange();
        }
    }

    private static final String LOG_SEARCH_SCHEMA = """
            {
              "type": "object",
              "properties": {
                "keyword": {"type": "string", "description": "检索关键字（匹配日志内容）"},
                "startTime": {"type": "string", "description": "起始时间，ISO-8601，可选，默认最近1小时"},
                "endTime": {"type": "string", "description": "结束时间，ISO-8601，可选，默认现在"},
                "limit": {"type": "integer", "description": "返回条数，默认20，上限200"}
              },
              "required": ["keyword"]
            }
            """;

    private static final String ENTITY_QUERY_SCHEMA = """
            {
              "type": "object",
              "properties": {
                "startTime": {"type": "string", "description": "起始时间，ISO-8601，可选，默认最近1小时"},
                "endTime": {"type": "string", "description": "结束时间，ISO-8601，可选，默认现在"},
                "orderNo": {"type": "string", "description": "订单号精确过滤，可选"},
                "limit": {"type": "integer", "description": "返回条数，默认20，上限200"}
              }
            }
            """;

    private static final String DESCRIBE_ENTITY_SCHEMA = """
            {
              "type": "object",
              "properties": {}
            }
            """;

    /**
     * 跨源对齐入参：日志侧关键字 + 统一时间窗 + 桶粒度。
     * <p>
     * 刻意<b>不</b>暴露 dsId / 表名 / 索引名：用哪个源由后端按能力发现（P1 决定），模型只描述"要查什么"。
     */
    private static final String CROSS_SOURCE_SCHEMA = """
            {
              "type": "object",
              "properties": {
                "keyword": {"type": "string", "description": "日志侧检索关键字（匹配日志内容，必填）"},
                "entityCode": {"type": "string", "description": "要与日志对齐的实体编码，可选（如 orders / payments）；不传则取默认实体源"},
                "startTime": {"type": "string", "description": "起始时间，ISO-8601，可选，默认最近1小时"},
                "endTime": {"type": "string", "description": "结束时间，ISO-8601，可选，默认现在"},
                "bucketMinutes": {"type": "integer", "description": "时间桶粒度（分钟），默认1；对齐时间线时建议1~5"},
                "limit": {"type": "integer", "description": "每一侧最多取多少条，默认50，上限200"}
              },
              "required": ["keyword"]
            }
            """;

    /**
     * M7a 现象归纳入参：只暴露"要查什么"（关键字 + 时间窗 + 返回多少个现象组）。
     * <p>
     * 刻意<b>不</b>暴露：{@code k}（IQR 系数）、{@code minSample}、桶粒度、分组维度、dsId、索引名、字段名。
     * 理由见 M7 设计文档 §5.1 与 M4 观测到的"模型反复试参数"问题——统计口径由后端固定，模型没有调参口。
     */
    private static final String ANOMALY_SUMMARY_SCHEMA = """
            {
              "type": "object",
              "properties": {
                "keyword": {"type": "string", "description": "检索关键字（匹配日志内容，必填）"},
                "startTime": {"type": "string", "description": "起始时间，ISO-8601，可选，默认最近1小时"},
                "endTime": {"type": "string", "description": "结束时间，ISO-8601，可选，默认现在"},
                "limit": {"type": "integer", "description": "最多返回多少个现象组，默认20，上限50"}
              },
              "required": ["keyword"]
            }
            """;

    /**
     * M8 故障期/基线对比入参：**只有关键字与行数上限**——故障窗由请求侧提供并经 {@code ToolContext} 注入，
     * 时间窗/桶粒度/offset 一律不出现在 schema 里（与 {@code tenantId} 同一条纪律）。
     */
    private static final String FAULT_WINDOW_SCHEMA = """
            {
              "type": "object",
              "properties": {
                "keyword": {"type": "string", "description": "检索关键字（匹配日志内容，必填）"},
                "limit": {"type": "integer", "description": "最多返回多少个时间桶，默认100，上限200"}
              },
              "required": ["keyword"]
            }
            """;

}
