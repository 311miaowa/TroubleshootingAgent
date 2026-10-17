package cn.bugstack.ai.infrastructure.gateway.datasource;

import cn.bugstack.ai.domain.agent.adapter.port.datasource.DataSourceCapability;
import cn.bugstack.ai.domain.agent.adapter.port.datasource.ILogSearchProvider;
import cn.bugstack.ai.domain.agent.adapter.repository.IDataSourceRepository;
import cn.bugstack.ai.domain.agent.model.valobj.datasource.EntityMeta;
import cn.bugstack.ai.domain.agent.model.valobj.datasource.HealthStatus;
import cn.bugstack.ai.domain.agent.model.valobj.datasource.LogSearchQuery;
import cn.bugstack.ai.domain.agent.model.valobj.datasource.QueryResult;
import cn.bugstack.ai.domain.agent.model.valobj.datasource.TimeRange;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Elasticsearch 日志 Provider（M3.0 起直连 ES 的 {@code _search} HTTP API）
 * <p>
 * 设计依据（M3 文档 §2 决策 1）：
 * <ul>
 *     <li>从 {@code ai_data_source.conn_config} 读 {@code host}（必填）与可选 {@code auth}（如
 *         {@code "ApiKey xxx"} / {@code "Basic xxx"}），直连 ES 的 {@code _search}；</li>
 *     <li>{@code queryBody} 由 {@code query_template} 渲染，<b>不由模型提供</b>；</li>
 *     <li>响应为结构化 JSON（{@code hits.hits[]._source / _index / _id}），据此逐条回指原文——
 *         行级 {@code _rawRef = index#id}，结果级 {@code rawRef = es://...}；</li>
 *     <li>返回前按 {@code max_rows} 截断；字段裁剪与敏感脱敏在结果映射时一并处理。</li>
 * </ul>
 * <p>
 * <b>为什么直连</b>：ES MCP 的 {@code search} 返回平铺文本且<b>不含 {@code _id}</b>，证据面板无法逐条回指；
 * 离线仓库又没有 ES 官方 Java client，故用 JDK 内置 {@link HttpClient} 直连（零新增依赖）。
 * <p>
 * <b>边界</b>：ES MCP（5007）仍保留在装配链里，但本 Provider 不再依赖它做查询；
 * 本地 ES 默认无认证，{@code auth} 可省略。
 *
 * @author miaowazhongzi
 * 2025/6/27 18:29
 */
@Slf4j
public class ElasticsearchProvider implements ILogSearchProvider {

    /**
     * 本 Provider 负责的 {@code ai_data_source.ds_type}（工厂也复用这个常量，避免两处写字面量）
     */
    public static final String DS_TYPE = "elasticsearch";

    private final String dsId;

    private final IDataSourceRepository.DataSourceConfig config;

    private final IDataSourceRepository repository;

    private final HttpClient httpClient;

    public ElasticsearchProvider(String dsId,
                                 IDataSourceRepository.DataSourceConfig config,
                                 IDataSourceRepository repository) {
        this.dsId = dsId;
        this.config = config;
        this.repository = repository;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(timeoutMs()))
                .build();
    }

    @Override
    public String dsId() {
        return dsId;
    }

    @Override
    public String dsType() {
        return DS_TYPE;
    }

    @Override
    public Set<DataSourceCapability> capabilities() {
        return Set.of(DataSourceCapability.ENTITY_META, DataSourceCapability.LOG_SEARCH);
    }

    @Override
    public HealthStatus healthCheck() {
        EsEndpoint endpoint = resolveEndpoint();
        if (endpoint == null) {
            return HealthStatus.down("数据源未配置直连 conn_config.host：dsId=" + dsId);
        }
        long start = System.currentTimeMillis();
        try {
            HttpResponse<String> resp = send("GET", endpoint, "/_cluster/health", null);
            HealthStatus status = resp.statusCode() == 200
                    ? HealthStatus.up()
                    : HealthStatus.down("健康检查 HTTP " + resp.statusCode() + "：dsId=" + dsId);
            status.setCostMs(System.currentTimeMillis() - start);
            status.setMessage(resp.body());
            return status;
        } catch (Exception e) {
            HealthStatus status = HealthStatus.down("健康检查失败 dsId=" + dsId + "：" + e.getMessage(), e);
            status.setCostMs(System.currentTimeMillis() - start);
            return status;
        }
    }

    @Override
    public List<EntityMeta> listEntities() {
        return repository.queryEnabledEntities(dsId);
    }

    @Override
    public QueryResult search(LogSearchQuery query) {
        QueryResult empty = QueryResult.empty(dsId, null);
        if (query == null || query.getKeyword() == null || query.getKeyword().isBlank()) {
            empty.getWarnings().add("keyword 不能为空");
            return empty;
        }

        EsEndpoint endpoint = resolveEndpoint();
        if (endpoint == null) {
            empty.getWarnings().add("数据源未配置直连 conn_config.host：dsId=" + dsId);
            return empty;
        }

        IDataSourceRepository.EntityConfig entityConfig = findLogEntity();
        if (entityConfig == null) {
            empty.getWarnings().add("数据源无 log_search 实体配置：dsId=" + dsId);
            return empty;
        }

        TimeRange range = query.timeRangeOrDefault();
        int size = clampLimit(query.getLimit());

        String queryBody = renderQueryBody(entityConfig.getQueryTemplate(), query.getKeyword(), range, size);

        // 只读守卫（ES 侧的 SqlTemplateRenderer 对等物）：请求方法/路径虽已硬编码为 POST + _search，
        // 但 physical_name 会拼进 URL 路径、query_template 渲染结果会成为请求体，两者都来自库表，
        // 必须过标识符正则 + DSL 键白名单后再发出。
        String physicalNameError = EsQueryGuard.validatePhysicalName(entityConfig.getPhysicalName());
        if (physicalNameError != null) {
            log.warn("ES 物理索引名未通过白名单校验 dsId={} entityCode={}：{}",
                    dsId, entityConfig.getEntityCode(), physicalNameError);
            empty.getWarnings().add("ES 物理索引名未通过白名单校验：" + physicalNameError);
            return empty;
        }
        String bodyError = EsQueryGuard.validateSearchBody(queryBody);
        if (bodyError != null) {
            log.warn("ES 查询体未通过只读白名单校验 dsId={} entityCode={}：{}",
                    dsId, entityConfig.getEntityCode(), bodyError);
            empty.getWarnings().add("ES 查询体未通过只读白名单校验：" + bodyError);
            return empty;
        }

        try {
            HttpResponse<String> resp = send("POST", endpoint,
                    "/" + entityConfig.getPhysicalName() + "/_search", queryBody);
            if (resp.statusCode() != 200) {
                empty.getWarnings().add("ES 查询失败 HTTP " + resp.statusCode() + "：" + resp.body());
                return empty;
            }
            return mapToQueryResult(entityConfig, range, resp.body(), size);
        } catch (Exception e) {
            empty.getWarnings().add("ES 查询异常：" + e.getMessage());
            return empty;
        }
    }

    @Override
    public void close() {
        // JDK HttpClient 无显式 close、无连接池，无需资源回收。
    }

    private IDataSourceRepository.EntityConfig findLogEntity() {
        List<IDataSourceRepository.EntityConfig> configs = repository.queryEnabledEntityConfigs(dsId);
        if (configs == null) {
            return null;
        }
        return configs.stream()
                .filter(c -> "log_search".equals(c.getCapability()))
                .findFirst()
                .orElse(null);
    }

    private int timeoutMs() {
        return config.getTimeoutMs() > 0 ? config.getTimeoutMs() : 5000;
    }

    private int clampLimit(int requestLimit) {
        int max = config.getMaxRows() > 0 ? config.getMaxRows() : 200;
        int limit = requestLimit > 0 ? requestLimit : 20;
        return Math.min(limit, max);
    }

    /**
     * 渲染 query_template：只替换白名单占位符，绝不拼模型提供的任意 DSL。
     */
    private String renderQueryBody(String template, String keyword, TimeRange range, int size) {
        if (template == null || template.isBlank()) {
            throw new IllegalStateException("实体缺少 query_template：dsId=" + dsId);
        }
        Instant start = range.getStart();
        Instant end = range.getEnd();
        return template
                .replace("${keyword}", jsonStringLiteral(keyword))
                .replace("${start}", jsonStringLiteral(start.toString()))
                .replace("${end}", jsonStringLiteral(end.toString()))
                .replace("${size}", String.valueOf(size));
    }

    /**
     * 生成 JSON 字符串字面量（不含外层引号，模板里已有引号包裹占位符）
     */
    private String jsonStringLiteral(String value) {
        String json = JSON.toJSONString(value);
        return json.length() >= 2 ? json.substring(1, json.length() - 1) : "";
    }

    /**
     * 解析 conn_config，得到直连 ES 的端点。
     * <p>
     * 期望 {@code {"host":"http://127.0.0.1:9200","auth":"ApiKey xxx"(可选)}}。
     * 未配置 / 缺 host 时返回 null（由调用方按"未配置直连"处理）。
     */
    private EsEndpoint resolveEndpoint() {
        String conn = config.getConnConfig();
        if (conn == null || conn.isBlank()) {
            log.warn("数据源未配置 conn_config，无法直连 ES：dsId={}", dsId);
            return null;
        }
        try {
            JSONObject obj = JSONObject.parseObject(conn);
            String host = obj.getString("host");
            if (host == null || host.isBlank()) {
                log.warn("conn_config 缺少 host：dsId={}", dsId);
                return null;
            }
            while (host.endsWith("/")) {
                host = host.substring(0, host.length() - 1);
            }
            return new EsEndpoint(host, obj.getString("auth"));
        } catch (Exception e) {
            log.warn("解析 ES 直连 conn_config 失败 dsId={}：{}", dsId, e.getMessage());
            return null;
        }
    }

    private HttpResponse<String> send(String method, EsEndpoint endpoint, String path, String body)
            throws IOException, InterruptedException {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(endpoint.host() + path))
                .timeout(Duration.ofMillis(timeoutMs()))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json");
        if (endpoint.auth() != null && !endpoint.auth().isBlank()) {
            builder.header("Authorization", endpoint.auth());
        }
        if ("GET".equals(method)) {
            builder.GET();
        } else {
            builder.POST(HttpRequest.BodyPublishers.ofString(body == null ? "" : body, StandardCharsets.UTF_8));
        }
        return httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    /**
     * 把 ES {@code _search} 的结构化响应映射成统一 QueryResult，并落地 rawRef。
     * <p>
     * 注入顺序（M3 纪律）：先按字段字典裁剪 + 脱敏，<b>最后</b>再注入行级 {@code _rawRef}
     * （否则会被 {@link #applyFieldPolicy} 裁掉）。{@code _rawRef} 不进 {@code columns}。
     */
    private QueryResult mapToQueryResult(IDataSourceRepository.EntityConfig entityConfig,
                                         TimeRange range, String responseBody, int requestedSize) {
        QueryResult result = QueryResult.builder()
                .dsId(dsId)
                .entityCode(entityConfig.getEntityCode())
                .build();

        JSONObject json = JSON.parseObject(responseBody);
        if (json == null) {
            result.getWarnings().add("ES 响应为空");
            return result;
        }
        JSONObject hits = json.getJSONObject("hits");
        if (hits == null) {
            result.getWarnings().add("ES 响应缺少 hits");
            return result;
        }
        JSONObject totalObj = hits.getJSONObject("total");
        long total = totalObj == null ? 0 : totalObj.getLongValue("value");
        JSONArray hitArray = hits.getJSONArray("hits");

        List<Map<String, Object>> rows = new ArrayList<>();
        List<String> rawRefs = new ArrayList<>();
        if (hitArray != null) {
            for (int i = 0; i < hitArray.size(); i++) {
                JSONObject hit = hitArray.getJSONObject(i);
                String index = hit.getString("_index");
                String id = hit.getString("_id");
                JSONObject source = hit.getJSONObject("_source");
                Map<String, Object> row = new LinkedHashMap<>();
                if (source != null) {
                    row.putAll(source);
                }
                rows.add(row);
                rawRefs.add(index + "#" + id);
            }
        }

        rows = applyFieldPolicy(entityConfig.getEntityCode(), rows);

        // 命中总数超过已返回条数 → 被 max_rows 截断（ES 已按 size 返回，rows.size() <= requestedSize）
        boolean truncated = total > rows.size();

        // 注入行级 _rawRef（在裁剪/脱敏之后，避免被裁掉）
        for (int i = 0; i < rows.size() && i < rawRefs.size(); i++) {
            rows.get(i).put(QueryResult.RAW_REF_KEY, rawRefs.get(i));
        }

        result.setRows(rows);
        result.setColumns(deriveColumns(rows));
        result.setTotal(total > 0 ? total : rows.size());
        result.setTruncated(truncated);
        result.setRawRef(buildRawRef(entityConfig, range));
        return result;
    }

    /**
     * 结果级 rawRef（块级引用，证据块头部用）
     */
    private String buildRawRef(IDataSourceRepository.EntityConfig entityConfig, TimeRange range) {
        return "es://" + dsId + "/" + entityConfig.getEntityCode() + "/" + entityConfig.getPhysicalName()
                + "@" + range.getStart() + "~" + range.getEnd();
    }

    /**
     * 按字段字典裁剪列 + 对 sensitive=1 的字段脱敏
     */
    private List<Map<String, Object>> applyFieldPolicy(String entityCode, List<Map<String, Object>> rows) {
        Map<String, List<EntityMeta.FieldMeta>> fieldsByEntity = repository.queryEnabledFields(dsId);
        List<EntityMeta.FieldMeta> fields = fieldsByEntity == null ? null : fieldsByEntity.get(entityCode);
        if (fields == null || fields.isEmpty() || rows.isEmpty()) {
            return rows;
        }

        Set<String> allowed = new LinkedHashSet<>();
        Map<String, Boolean> sensitiveMap = new HashMap<>();
        for (EntityMeta.FieldMeta f : fields) {
            allowed.add(f.getFieldName());
            sensitiveMap.put(f.getFieldName(), f.isSensitive());
        }

        List<Map<String, Object>> masked = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (String key : row.keySet()) {
                if (!allowed.contains(key)) {
                    continue;
                }
                Object value = row.get(key);
                if (Boolean.TRUE.equals(sensitiveMap.get(key)) && value != null) {
                    value = mask(value.toString());
                }
                out.put(key, value);
            }
            masked.add(out);
        }
        return masked;
    }

    private String mask(String value) {
        if (value.length() <= 4) {
            return "****";
        }
        return value.substring(0, 2) + "****" + value.substring(value.length() - 2);
    }

    private List<String> deriveColumns(List<Map<String, Object>> rows) {
        if (rows.isEmpty()) {
            return new ArrayList<>();
        }
        List<String> columns = new ArrayList<>();
        for (String key : rows.get(0).keySet()) {
            if (!QueryResult.RAW_REF_KEY.equals(key)) {
                columns.add(key);
            }
        }
        return columns;
    }

    /**
     * ES 直连端点（host + 可选 auth）
     */
    private record EsEndpoint(String host, String auth) {
    }

}
