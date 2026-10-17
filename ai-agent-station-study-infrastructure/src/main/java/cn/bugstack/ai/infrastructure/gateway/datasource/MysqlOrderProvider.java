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
 * MySQL 订单只读 Provider（第二个数据源）
 * <p>
 * 设计依据（M1 文档 §7）：
 * <ul>
 *     <li>连接池照抄 {@code DataSourceConfig#pgVectorDataSource} 的写法，但<b>不</b>照抄
 *         {@code setInitializationFailTimeout(1)}——业务库启动不可用不应让应用起不来；</li>
 *     <li>只读账号 + {@code ds.setReadOnly(true)}；</li>
 *     <li>SQL 只来自 {@code query_template} 命名占位符（{@code ${start}/${end}/${limit}}），
 *         值走 {@link JdbcTemplate}（底层 PreparedStatement）参数绑定，禁止模型拼 SQL；</li>
 *     <li>{@code LIMIT = min(请求值, max_rows)}。</li>
 * </ul>
 * <p>
 * 连接池<b>懒加载</b>（首次查询才建），且连接密码来自 {@code conn_config.passwordEnv} 指向的环境变量，
 * 不落库。
 *
 * @author miaowazhongzi
 * 2025/6/27 18:29
 */
@Slf4j
public class MysqlOrderProvider implements IEntityQueryProvider {

    /**
     * 订单表 create_time 为 datetime（无时区），按服务器时区（与 JDBC url serverTimezone 一致）解释
     */
    private static final ZoneId DB_ZONE = ZoneId.of("Asia/Shanghai");

    private final String dsId;

    private final IDataSourceRepository.DataSourceConfig config;

    private final IDataSourceRepository repository;

    private volatile HikariDataSource dataSource;

    private volatile JdbcTemplate jdbcTemplate;

    /**
     * 本 Provider 负责的 {@code ai_data_source.ds_type}（工厂也复用这个常量，避免两处写字面量）
     */
    public static final String DS_TYPE = "mysql";

    public MysqlOrderProvider(String dsId,
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
        return Set.of(DataSourceCapability.ENTITY_META, DataSourceCapability.ENTITY_QUERY);
    }

    @Override
    public HealthStatus healthCheck() {
        long start = System.currentTimeMillis();
        try {
            jdbcTemplate().queryForObject("SELECT 1", Integer.class);
            HealthStatus status = HealthStatus.up();
            status.setCostMs(System.currentTimeMillis() - start);
            return status;
        } catch (Exception e) {
            // 失败也记耗时：与 connectionTimeout(5000) 对照，可一眼区分"被拒绝"还是"超时"
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

        // P1-2：模板渲染与"通用过滤"统一走 SqlTemplateRenderer（按占位符出现顺序对位参数，
        // 因此模板里把 ${filters} 放在哪里都能正确对位；订单模板未启用 ${filters}，行为与之前一致）
        SqlTemplateRenderer.Rendered rendered;
        try {
            rendered = SqlTemplateRenderer.render(
                    entityConfig.getQueryTemplate(),
                    SqlTemplateRenderer.values(
                            toTimestamp(range.getStart()), toTimestamp(range.getEnd()), orderNo, limit),
                    query.getFilters(),
                    fields,
                    SqlTemplateRenderer.Dialect.MYSQL);
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

            // 注入行级 _rawRef（order_no 主键级引用，逐条回指订单行）
            for (Map<String, Object> row : rows) {
                Object orderNoVal = row.get("order_no");
                row.put(QueryResult.RAW_REF_KEY, "order_no=" + (orderNoVal == null ? "" : orderNoVal));
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
            log.info("MySQL 查询完成 dsId={} entityCode={} rows={} truncated={} filters={}",
                    dsId, entityConfig.getEntityCode(), rows.size(), truncated,
                    query.getFilters() == null ? 0 : query.getFilters().size());
            return result;
        } catch (Exception e) {
            empty.getWarnings().add("MySQL 查询异常：" + e.getMessage());
            return empty;
        }
    }

    @Override
    public void close() {
        HikariDataSource ds = dataSource;
        if (ds != null) {
            try {
                ds.close();
                log.info("MySQL 数据源连接池已关闭 dsId={}", dsId);
            } catch (Exception e) {
                log.warn("关闭 MySQL 连接池失败 dsId={}：{}", dsId, e.getMessage());
            }
        }
    }

    private IDataSourceRepository.EntityConfig findEntityConfig() {
        List<IDataSourceRepository.EntityConfig> configs = repository.queryEnabledEntityConfigs(dsId);
        if (configs == null) {
            return null;
        }
        return configs.stream()
                .filter(c -> "entity_query".equals(c.getCapability()))
                .findFirst()
                .orElse(null);
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
                ? "com.mysql.cj.jdbc.Driver" : driverClassName);
        ds.setJdbcUrl(jdbcUrl);
        ds.setUsername(username);
        ds.setPassword(password == null ? "" : password);
        ds.setReadOnly(true);
        ds.setMaximumPoolSize(5);
        ds.setMinimumIdle(1);
        ds.setConnectionTimeout(5000);
        // 关键：业务库不可用不应让应用启动失败，跳过连接池初始化期的快速失败
        ds.setInitializationFailTimeout(-1);
        ds.setPoolName("MysqlOrderHikariPool");
        this.dataSource = ds;
        log.info("MySQL 数据源连接池已构建 dsId={}（readOnly=true）", dsId);
        return ds;
    }

    /**
     * 取该实体的字段字典（模板渲染的白名单 + 结果裁剪/脱敏共用同一份，避免重复查询）
     */
    private List<EntityMeta.FieldMeta> fieldsOf(String entityCode) {
        Map<String, List<EntityMeta.FieldMeta>> fieldsByEntity = repository.queryEnabledFields(dsId);
        if (fieldsByEntity == null) {
            return List.of();
        }
        List<EntityMeta.FieldMeta> fields = fieldsByEntity.get(entityCode);
        return fields == null ? List.of() : fields;
    }

    private int clampLimit(int requestLimit) {
        int max = config.getMaxRows() > 0 ? config.getMaxRows() : 200;
        int limit = requestLimit > 0 ? requestLimit : 20;
        return Math.min(limit, max);
    }

    /**
     * Instant → Timestamp：datetime 无时区，按服务器时区转"墙上时间"
     */
    private Timestamp toTimestamp(Instant instant) {
        return Timestamp.valueOf(LocalDateTime.ofInstant(instant, DB_ZONE));
    }

    /**
     * 按字段字典裁剪列 + 对 sensitive=1 的字段脱敏
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

    /**
     * 结果级 rawRef（块级引用，证据块头部用）
     */
    private String buildRawRef(IDataSourceRepository.EntityConfig entityConfig, TimeRange range) {
        return "mysql://" + dsId + "/" + entityConfig.getEntityCode() + "/" + entityConfig.getPhysicalName()
                + "@" + range.getStart() + "~" + range.getEnd();
    }

}
