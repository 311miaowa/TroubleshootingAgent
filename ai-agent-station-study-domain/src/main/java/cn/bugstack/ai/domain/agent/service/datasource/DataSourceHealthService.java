package cn.bugstack.ai.domain.agent.service.datasource;

import cn.bugstack.ai.domain.agent.adapter.port.datasource.IDataSourceProvider;
import cn.bugstack.ai.domain.agent.adapter.port.datasource.IDataSourceRegistry;
import cn.bugstack.ai.domain.agent.adapter.repository.IDataSourceRepository;
import cn.bugstack.ai.domain.agent.model.valobj.datasource.EntityMeta;
import cn.bugstack.ai.domain.agent.model.valobj.datasource.HealthStatus;
import cn.bugstack.ai.domain.agent.model.valobj.datasource.SourceHealth;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * 数据源健康服务（M4）
 * <p>
 * 把"数据源是否真的可用"从<b>查询期才暴露的泛化错误</b>，变成<b>启动可见、接口可查、UI 可显示</b>的
 * 一等事实。本类做三件事：
 * <ol>
 *     <li>遍历注册表，对每个 Provider 跑一次 {@code healthCheck()}，并把结果规范化成
 *         {@link SourceHealth}（错误归因 + 实体摘要 + 可操作 hint）；</li>
 *     <li>把结果打印成日志：不可用 → <b>WARN</b>（含 dsId 与"要注入哪个环境变量"），全部可用 → INFO；</li>
 *     <li>带 TTL 的进程内缓存：端点默认走缓存，{@code refresh=true} 强制重查，热更新重装 Provider 时失效。</li>
 * </ol>
 * <p>
 * <b>为什么要有 hint</b>：M4 观测到的真实故障是"未注入 {@code DS_ORDER_MYSQL_PASSWORD} → 空密码 →
 * 首次查询才报 {@code Failed to obtain JDBC Connection}"。Provider 只能说出"连不上"，
 * 说不出"你少注入了一个环境变量"；后者必须从 {@code conn_config} 推导，故放在本服务。
 * <p>
 * <b>纪律</b>：hint 只暴露<b>环境变量名</b>与缺失事实，绝不输出密码本身，也不输出完整连接串。
 *
 * @author miaowazhongzi
 * 2025/6/27 18:29
 */
@Slf4j
@Service
public class DataSourceHealthService {

    @Resource
    private IDataSourceRegistry dataSourceRegistry;

    @Resource
    private IDataSourceRepository dataSourceRepository;

    /**
     * 健康结果缓存 TTL（毫秒）。决策 1 选 A：启动一次 + 缓存 + 端点支持强制刷新，
     * 避免把"健康检查"变成被高频调用的写路径（MySQL 会真连库、ES 会真发 HTTP）。
     */
    private long ttlMs = 30_000L;

    /**
     * TTL 注入点：Spring 读 {@code xfg.datasource.health.ttl-ms}；单测可直接调用以验证过期行为
     */
    @Value("${xfg.datasource.health.ttl-ms:30000}")
    public void setTtlMs(long ttlMs) {
        this.ttlMs = ttlMs;
    }

    /**
     * 缓存锁：并发请求下只允许一次健康检查在跑（防击穿）
     */
    private final Object cacheLock = new Object();

    /**
     * 进程内单一缓存（volatile 发布，读路径无锁）
     */
    private volatile CacheEntry cache;

    /**
     * 端点入口：默认走缓存，{@code refresh=true} 忽略缓存并回写
     *
     * @param refresh 是否强制重新检查
     */
    public Snapshot health(boolean refresh) {
        CacheEntry current = cache;
        if (!refresh && isFresh(current)) {
            return new Snapshot(current.checkedAt, true, current.sources);
        }

        synchronized (cacheLock) {
            // 双重检查：等锁期间可能已有别的线程刷过缓存
            current = cache;
            if (!refresh && isFresh(current)) {
                return new Snapshot(current.checkedAt, true, current.sources);
            }
            long checkedAt = System.currentTimeMillis();
            List<SourceHealth> sources = checkAll();
            cache = new CacheEntry(checkedAt, sources);
            return new Snapshot(checkedAt, false, sources);
        }
    }

    /**
     * 真实跑一遍健康检查（<b>不使用</b>缓存），按 dsId 排序返回
     * <p>
     * 排序是为了让日志与端点的输出顺序稳定，便于比对与断言。
     */
    public List<SourceHealth> checkAll() {
        List<IDataSourceProvider> providers = dataSourceRegistry.listByTenant(null);
        if (providers == null || providers.isEmpty()) {
            log.warn("数据源健康检查：注册表中没有任何 Provider");
            return new ArrayList<>();
        }

        Map<String, IDataSourceRepository.DataSourceConfig> configs = queryConfigs();
        List<SourceHealth> list = new ArrayList<>(providers.size());
        for (IDataSourceProvider provider : providers) {
            list.add(checkOne(provider, configs.get(provider.dsId())));
        }
        list.sort(Comparator.comparing(SourceHealth::getDsId, Comparator.nullsLast(Comparator.naturalOrder())));
        return list;
    }

    /**
     * 使缓存立即失效
     * <p>
     * 热更新会重装 Provider（旧实例被关闭），旧快照必须作废，否则 UI 会继续显示已下线数据源的状态。
     */
    public void invalidate() {
        cache = null;
    }

    /**
     * 跑一次健康检查（绕过缓存）并打印汇总日志，同时回写缓存
     * <p>
     * 不抛异常、不影响启动（决策 2：降级启动 + WARN，不 fail-fast）——
     * "某个只读源挂了"不该演变成"整个 Agent 服务起不来"。
     */
    public void logReport() {
        Snapshot snapshot = health(true);
        List<SourceHealth> sources = snapshot.getSources();
        if (sources.isEmpty()) {
            return;
        }

        int unhealthy = 0;
        for (SourceHealth source : sources) {
            if (source.isHealthy()) {
                log.info("数据源健康 dsId={} dsType={} costMs={} entities={}",
                        source.getDsId(), source.getDsType(), source.getCostMs(), source.getEntities().size());
            } else {
                unhealthy++;
                // 关键：WARN 必须同时给出"哪个源"和"怎么修"（hint 里含缺失的环境变量名）
                log.warn("数据源不可用 dsId={} dsType={} costMs={} 原因={} 建议={}",
                        source.getDsId(), source.getDsType(), source.getCostMs(),
                        source.getMessage(), source.getHint());
            }
        }

        if (unhealthy > 0) {
            log.warn("数据源健康检查未全部通过：共 {} 个，{} 个不可用。应用继续启动（不 fail-fast），"
                    + "指向这些数据源的查询会降级返回空结果并在 warnings 中给出原因。", sources.size(), unhealthy);
        }
    }

    /**
     * 单个 Provider 的健康检查：Provider 自述 → 规范化
     */
    private SourceHealth checkOne(IDataSourceProvider provider, IDataSourceRepository.DataSourceConfig config) {
        long start = System.currentTimeMillis();
        HealthStatus status;
        try {
            status = provider.healthCheck();
        } catch (Exception e) {
            // Provider 约定不抛异常；这里兜底，避免一个坏 Provider 让整轮检查中断
            status = HealthStatus.down("健康检查异常：" + e.getMessage(), e);
        }
        if (status == null) {
            status = HealthStatus.down("健康检查未返回结果");
        }

        boolean healthy = status.isHealthy();
        long costMs = status.getCostMs() > 0 ? status.getCostMs() : System.currentTimeMillis() - start;

        return SourceHealth.builder()
                .dsId(provider.dsId())
                .dsType(provider.dsType())
                .healthy(healthy)
                .message(healthy ? null : status.getMessage())
                .costMs(costMs)
                .hint(healthy ? null : hintOf(provider.dsType(), config))
                .entities(briefEntities(provider))
                .build();
    }

    /**
     * 从 {@code conn_config} 推导<b>可操作</b>提示，取第一条命中者（M4 文档 §4.2）
     * <p>
     * 只输出"事实 + 下一步动作"，拿不准时返回 null（保留原始 message，不编造解释）。
     */
    private String hintOf(String dsType, IDataSourceRepository.DataSourceConfig config) {
        JSONObject conn = parseConn(config);
        if (conn == null) {
            return null;
        }

        // P1-2：规则 1/2 从"仅 mysql"放宽到"任何走 JDBC + passwordEnv 的类型"（mysql / postgres / …），
        // 否则新增一个数据源类型时，同样的问题只会回 null，白白丢掉 M4 最有价值的那条可操作提示。
        if (isJdbcType(dsType)) {
            String passwordEnv = conn.getString("passwordEnv");
            if (passwordEnv != null && !passwordEnv.isBlank() && System.getenv(passwordEnv) == null) {
                return "未能取到连接密码：请注入环境变量 " + passwordEnv + "（来源 ai_data_source.conn_config.passwordEnv）";
            }
            if (isBlank(passwordEnv)) {
                // 空档补齐（M4 §6.1 记录的规则空档）：连"该用哪个环境变量"都没配，
                // Provider 会按空密码去连，必然认证失败——必须说清是"配置缺了键"而不是"没注入变量"。
                return "conn_config 缺少 passwordEnv（连接密码的环境变量名）："
                        + "Provider 会按空密码连接，必然失败；请补成 {\"passwordEnv\":\"<环境变量名>\"}";
            }
            if (isBlank(conn.getString("jdbcUrl"))) {
                return "conn_config 缺少 jdbcUrl";
            }
            return null;
        }

        if ("elasticsearch".equals(dsType) && isBlank(conn.getString("host"))) {
            return "conn_config 缺少 host";
        }

        return null;
    }

    /**
     * 是否"走 JDBC + 环境变量取密码"的数据源类型（conn_config 用 jdbcUrl / passwordEnv 那套）
     */
    private boolean isJdbcType(String dsType) {
        return "mysql".equals(dsType) || "postgres".equals(dsType) || "postgresql".equals(dsType);
    }

    /**
     * 实体摘要：Provider 自述的能力清单；读不到就返回空表（不影响健康结论）
     */
    private List<SourceHealth.EntityBrief> briefEntities(IDataSourceProvider provider) {
        List<SourceHealth.EntityBrief> briefs = new ArrayList<>();
        List<EntityMeta> entities;
        try {
            entities = provider.listEntities();
        } catch (Exception e) {
            log.warn("读取数据源实体清单失败 dsId={}：{}", provider.dsId(), e.getMessage());
            return briefs;
        }
        if (entities == null) {
            return briefs;
        }
        for (EntityMeta entity : entities) {
            if (entity == null || entity.getEntityCode() == null) {
                continue;
            }
            // 同一实体可能挂多种能力（如 orders 同时有 entity_query / entity_meta），逐条列出
            briefs.add(SourceHealth.EntityBrief.builder()
                    .entityCode(entity.getEntityCode())
                    .entityName(entity.getEntityName())
                    .capability(entity.getCapability())
                    .build());
        }
        return briefs;
    }

    private boolean isFresh(CacheEntry entry) {
        return entry != null && System.currentTimeMillis() - entry.checkedAt < ttlMs;
    }

    private Map<String, IDataSourceRepository.DataSourceConfig> queryConfigs() {
        try {
            Map<String, IDataSourceRepository.DataSourceConfig> configs = dataSourceRepository.queryEnabledDataSources();
            return configs == null ? Map.of() : configs;
        } catch (Exception e) {
            log.warn("读取数据源配置失败，hint 将不可用：{}", e.getMessage());
            return Map.of();
        }
    }

    private JSONObject parseConn(IDataSourceRepository.DataSourceConfig config) {
        if (config == null || isBlank(config.getConnConfig())) {
            return null;
        }
        try {
            return JSON.parseObject(config.getConnConfig());
        } catch (Exception e) {
            log.warn("解析数据源 conn_config 失败 dsId={}：{}", config.getDsId(), e.getMessage());
            return null;
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /**
     * 缓存条目
     */
    private static final class CacheEntry {

        private final long checkedAt;

        private final List<SourceHealth> sources;

        private CacheEntry(long checkedAt, List<SourceHealth> sources) {
            this.checkedAt = checkedAt;
            this.sources = sources;
        }

    }

    /**
     * 一次健康检查的结果快照：端点据此填充 {@code checkedAt / cached / sources}
     */
    @Data
    @AllArgsConstructor
    public static class Snapshot {

        /**
         * 本次结果的检查时刻（毫秒时间戳）；命中缓存时为缓存写入时刻
         */
        private final long checkedAt;

        /**
         * 结果是否来自缓存
         */
        private final boolean cached;

        /**
         * 各数据源的健康状态
         */
        private final List<SourceHealth> sources;

    }

}
