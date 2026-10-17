package cn.bugstack.ai.infrastructure.gateway.datasource;

import cn.bugstack.ai.domain.agent.adapter.port.datasource.DataSourceCapability;
import cn.bugstack.ai.domain.agent.adapter.port.datasource.IEntityQueryProvider;
import cn.bugstack.ai.domain.agent.adapter.repository.IDataSourceRepository;
import cn.bugstack.ai.domain.agent.model.valobj.datasource.EntityMeta;
import cn.bugstack.ai.domain.agent.model.valobj.datasource.EntityQuery;
import cn.bugstack.ai.domain.agent.model.valobj.datasource.HealthStatus;
import cn.bugstack.ai.domain.agent.model.valobj.datasource.QueryResult;
import cn.bugstack.ai.domain.agent.model.valobj.datasource.TimeRange;
import com.alibaba.fastjson.JSONObject;
import com.zaxxer.hikari.HikariDataSource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * PostgreSQL 实体只读 Provider（P1-2：第三个数据源，第一个"关系型 + 新数据库引擎"的源）
 * <p>
 * <b>为什么需要它</b>：M1 只验证过"接<b>第二个</b>源（MySQL）不改 Agent 侧代码"。再接一个
 * <b>新的 {@code ds_type}</b>（{@code postgres}）才能把这条承诺真正钉住——本类之外，
 * Agent 侧（工具工厂 / SSE / 证据面板 / 跨源关联）**一行都不用改**：
 * <ul>
 *     <li>工具 {@code query_<entityCode>} 由 {@code DataSourceToolCallbackFactory} 按能力自动生成；</li>
 *     <li>健康状态自动进 {@code /api/v1/datasource/health}（M4 的能力，无需适配）；</li>
 *     <li>跨源关联（P1）按能力发现来源，因此新源天然可参与时间线对齐。</li>
 * </ul>
 * <p>
 * <b>与 {@link MysqlOrderProvider} 的关系</b>：两者形状相同（只读、密码走 {@code passwordEnv}、
 * SQL 只来自 {@code query_template}、行数上限、敏感字段脱敏、rawRef 回指），差别只在<b>方言</b>与
 * <b>通用过滤</b>的实现（{@link SqlTemplateRenderer} 已按 {@link SqlTemplateRenderer.Dialect} 参数化）。
 * 目前刻意保持两个类各自独立（不抽基类）以降低改动风险；"抽关系型 Provider 基类"已登记为后续项。
 *
 * @author miaowazhongzi
 */
@Slf4j
public class PostgresEntityProvider implements IEntityQueryProvider {

    /**
     * 库里存的是 timestamp（无时区），按服务器时区解释——与 MySQL 订单源保持同一口径
     */
    private static final ZoneId DB_ZONE = ZoneId.of("Asia/Shanghai");

    /**
     * 本 Provider 负责的 {@code ai_data_source.ds_type}（工厂也复用这个常量，避免两处写字面量）
     */
    public static final String DS_TYPE = "postgres";

    /**
     * 行级回指优先使用的关联键（与 ai_order.order_no 对应，且未脱敏）
     */
    private static final String ROW_REF_KEY = "order_no";

    private final String dsId;

    private final IDataSourceRepository.DataSourceConfig config;

    private final IDataSourceRepository repository;

    private volatile HikariDataSource dataSource;

    private volatile JdbcTemplate jdbcTemplate;

    public PostgresEntityProvider(String dsId,
                                  IDataSourceRepository.DataSourceConfig config,
                                  IDataSourceRepository repository) {
        this.dsId = dsId;
        this.config = config;
        this.repository = repository;
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
        return Set.of(DataSourceCapability.ENTITY_META, DataSourceCapability.ENTITY_QUERY);    }

    @Override
    public HealthStatus healthCheck() {
        long start = System.currentTimeMillis();
        try {
            jdbcTemplate().queryForObject("SELECT 1", Integer.class);
            HealthStatus status = HealthStatus.up();
            status.setCostMs(System.currentTimeMillis() - start);
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
    public QueryResult query(EntityQuery query) {
        QueryResult empty = QueryResult.empty(dsId, query == null ? null : query.getEntityCode());
        if (query == null) {
            empty.getWarnings().add("EntityQuery 不能为空");
            return empty;
        }

        IDataSourceRepository.EntityConfig entityConfig = findEntityConfig();
        if (entityConfig == null) {
            empty.getWarnings().add("数据源无 entity_query 实体配置：dsId=" + dsId);
            return empty;
        }

        TimeRange range = query.timeRangeOrDefault();
        int limit = clampLimit(query.getLimit());
        String orderNo = query.getOrderNo();
        List<EntityMeta.FieldMeta> fields = fieldsOf(entityConfig.getEntityCode());

        SqlTemplateRenderer.Rendered rendered;
        try {
            rendered = SqlTemplateRenderer.render(
                    entityConfig.getQueryTemplate(),
                    SqlTemplateRenderer.values(
                            toTimestamp(range.getStart()), toTimestamp(range.getEnd()), orderNo, limit),
                    query.getFilters(),
                    fields,
                    SqlTemplateRenderer.Dialect.POSTGRES);
        } catch (Exception e) {
            empty.getWarnings().add("查询模板渲染失败：" + e.getMessage());
            return empty;
        }
        empty.getWarnings().addAll(rendered.getWarnings());

        try {
            List<Map<String, Object>> rows = jdbcTemplate().queryForList(rendered.getSql(), rendered.getArgsArray());
            rows = applyFieldPolicy(fields, rows);

            boolean truncated = rows.size() > limit;
            if (truncated) {
                rows = rows.subList(0, limit);
            }

            for (Map<String, Object> row : rows) {
                Object refValue = row.get(ROW_REF_KEY);
                if (refValue != null) {
                    row.put(QueryResult.RAW_REF_KEY, ROW_REF_KEY + "=" + refValue);
                }
            }

            QueryResult result = QueryResult.builder()
                    .dsId(dsId)
                    .entityCode(entityConfig.getEntityCode())
                    .rows(rows)
                    .columns(deriveColumns(rows))
                    .total(rows.size())
                    .truncated(truncated)
                    .rawRef(buildRawRef(entityConfig, range))
                    .build();
            result.getWarnings().addAll(empty.getWarnings());
            log.info("PostgreSQL 查询完成 dsId={} entityCode={} rows={} truncated={} filters={}",
                    dsId, entityConfig.getEntityCode(), rows.size(), truncated,
                    query.getFilters() == null ? 0 : query.getFilters().size());
            return result;
        } catch (Exception e) {
            empty.getWarnings().add("PostgreSQL 查询异常：" + e.getMessage());
            return empty;
        }
    }

    @Override
    public void close() {
        HikariDataSource ds = dataSource;
        if (ds != null) {
            try {
                ds.close();
                log.info("PostgreSQL 数据源连接池已关闭 dsId={}", dsId);
            } catch (Exception e) {
                log.warn("关闭 PostgreSQL 连接池失败 dsId={}：{}", dsId, e.getMessage());
            }
        }
    }

    private IDataSourceRepository.EntityConfig findEntityConfig() {
        List<IDataSourceRepository.EntityConfig> configs = repository.queryEnabledEntityConfigs(dsId);
        if (configs == null) {
            return null;
        }
        return configs.stream()
                .filter(c -> DataSourceCapability.ENTITY_QUERY.key().equals(c.getCapability()))
                .findFirst()
                .orElse(null);
    }

    private List<EntityMeta.FieldMeta> fieldsOf(String entityCode) {
        Map<String, List<EntityMeta.FieldMeta>> fieldsByEntity = repository.queryEnabledFields(dsId);
        return fieldsByEntity == null ? List.of() : fieldsByEntity.getOrDefault(entityCode, List.of());
    }

    private JdbcTemplate jdbcTemplate() {
        JdbcTemplate t = jdbcTemplate;
        if (t == null) {
            synchronized (this) {
                if (jdbcTemplate == null) {
                    jdbcTemplate = new JdbcTemplate(buildDataSource());
                }
                t = jdbcTemplate;
            }
        }
        return t;
    }

    private HikariDataSource buildDataSource() {
        JSONObject conn = JSONObject.parseObject(config.getConnConfig());
        String jdbcUrl = conn.getString("jdbcUrl");
        String username = conn.getString("username");
        String passwordEnv = conn.getString("passwordEnv");
        String driverClassName = conn.getString("driverClassName");
        String password = System.getenv(passwordEnv == null ? "" : passwordEnv);

        if (jdbcUrl == null || jdbcUrl.isBlank()) {
            throw new IllegalStateException("数据源 conn_config 缺少 jdbcUrl：dsId=" + dsId);
        }

        HikariDataSource ds = new HikariDataSource();
        ds.setDriverClassName(driverClassName == null || driverClassName.isBlank()
                ? "org.postgresql.Driver" : driverClassName);
        ds.setJdbcUrl(jdbcUrl);
        ds.setUsername(username);
        ds.setPassword(password == null ? "" : password);
        ds.setReadOnly(true);
        ds.setMaximumPoolSize(5);
        ds.setMinimumIdle(1);
        ds.setConnectionTimeout(timeoutMs());
        // 与 MySQL 订单源同一取舍：业务库不可用不应让应用起不来，失败留给健康检查与首次查询暴露
        ds.setInitializationFailTimeout(-1);
        ds.setPoolName("PgPaymentHikariPool");
        this.dataSource = ds;
        log.info("PostgreSQL 数据源连接池已构建 dsId={}（readOnly=true）", dsId);
        return ds;
    }

    private int timeoutMs() {
        return config.getTimeoutMs() > 0 ? config.getTimeoutMs() : 5000;
    }

    private int clampLimit(int requestLimit) {
        int max = config.getMaxRows() > 0 ? config.getMaxRows() : 200;
        int limit = requestLimit > 0 ? requestLimit : 20;
        return Math.min(limit, max);
    }

    private Timestamp toTimestamp(Instant instant) {
        return Timestamp.valueOf(LocalDateTime.ofInstant(instant, DB_ZONE));
    }

    /**
     * 按字段字典裁剪列 + 对 sensitive=1 的字段脱敏（与 MySQL 源同一策略）
     */
    private List<Map<String, Object>> applyFieldPolicy(List<EntityMeta.FieldMeta> fields, List<Map<String, Object>> rows) {
        if (fields == null || fields.isEmpty() || rows == null || rows.isEmpty()) {
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

    private String buildRawRef(IDataSourceRepository.EntityConfig entityConfig, TimeRange range) {
        return "pg://" + dsId + "/" + entityConfig.getEntityCode() + "/" + entityConfig.getPhysicalName()
                + "@" + range.getStart() + "~" + range.getEnd();
    }

}
