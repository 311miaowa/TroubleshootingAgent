package cn.bugstack.ai.test.m4;

import cn.bugstack.ai.domain.agent.adapter.port.datasource.DataSourceCapability;
import cn.bugstack.ai.domain.agent.adapter.port.datasource.IDataSourceProvider;
import cn.bugstack.ai.domain.agent.adapter.port.datasource.IDataSourceRegistry;
import cn.bugstack.ai.domain.agent.adapter.repository.IDataSourceRepository;
import cn.bugstack.ai.domain.agent.model.valobj.datasource.EntityMeta;
import cn.bugstack.ai.domain.agent.model.valobj.datasource.HealthStatus;
import cn.bugstack.ai.domain.agent.model.valobj.datasource.SourceHealth;
import cn.bugstack.ai.domain.agent.service.datasource.DataSourceHealthService;
import cn.bugstack.ai.domain.agent.service.datasource.DataSourceHealthService.Snapshot;
import org.junit.Assert;
import org.junit.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * M4.3 单测：数据源健康服务
 * <p>
 * 覆盖四件事：<b>hint 生成</b>（§4.2 四条规则）、<b>不健康映射</b>（message/costMs/entities 规范化）、
 * <b>缓存语义</b>（TTL 命中、refresh 绕过、invalidate 失效、logReport 回写）、<b>坏 Provider 兜底</b>。
 * <p>
 * <b>不依赖 Spring 上下文、数据库与网络</b>：Provider 与配置仓储都是手写桩。
 * 仅在验证"环境变量存在"分支时需要进程环境里确实有 {@code JAVA_HOME}（或 {@code PATH} 等，
 * 见 {@link #presentEnvName()}）。
 * <p>
 * 运行方式（离线仓库，`mvn test` 跑不了，原因见 M1 文档 §0 / §8 T1）：
 * <pre>
 * $env:JAVA_HOME='D:\develop\IntelliJ IDEA 2025.3.2\jbr'
 * mvn -o -B -DskipTests test-compile
 * # 再用 JUnitCore + 手工 classpath 直跑（classpath 组成见 docs/dev-ops/M4-datasource-health-design.md §6.2）
 * </pre>
 *
 * @author miaowazhongzi
 */
public class M4HealthServiceTest {

    /**
     * 故意不存在的环境变量名：用于验证"缺环境变量 → hint 指出变量名"这条规则
     */
    private static final String MISSING_ENV = "M4_TEST_ENV_DEFINITELY_NOT_SET";

    private static final String MYSQL_CONN_PREFIX =
            "{\"jdbcUrl\":\"jdbc:mysql://127.0.0.1:3306/ai-agent-station-study\",\"username\":\"ai_ro\"";

    /** P1-2 新增的 postgres 源连接配置前缀（不含密码，密码只留环境变量名） */
    private static final String PG_CONN_PREFIX =
            "{\"jdbcUrl\":\"jdbc:postgresql://127.0.0.1:15432/ai-rag-knowledge\",\"username\":\"postgres\"";

    // ------------------------------------------------------------------ hint 生成

    @Test
    public void mysql_missingPasswordEnvVar_hintNamesThatVariable() {
        DataSourceHealthService service = service(
                provider("ds_order_mysql", "mysql", HealthStatus.down(
                        "健康检查失败 dsId=ds_order_mysql：Failed to obtain JDBC Connection", null), "orders"),
                repo(config("ds_order_mysql", "mysql",
                        MYSQL_CONN_PREFIX + ",\"passwordEnv\":\"" + MISSING_ENV + "\"}")));

        SourceHealth source = service.checkAll().get(0);

        Assert.assertFalse("应判为不健康", source.isHealthy());
        Assert.assertTrue("message 应保留原始原因", source.getMessage().contains("Failed to obtain JDBC Connection"));
        Assert.assertTrue("message 应带 dsId 便于排查", source.getMessage().contains("ds_order_mysql"));
        Assert.assertNotNull("hint 不应为空", source.getHint());
        Assert.assertTrue("hint 必须指出缺哪个环境变量：" + source.getHint(),
                source.getHint().contains(MISSING_ENV));
        Assert.assertTrue("hint 应给出配置来源：" + source.getHint(),
                source.getHint().contains("passwordEnv"));
    }

    @Test
    public void mysql_passwordEnvVarPresent_butJdbcUrlMissing_hintIsJdbcUrl() {
        String present = presentEnvName();
        DataSourceHealthService service = service(
                provider("ds_x", "mysql", HealthStatus.down("健康检查失败 dsId=ds_x：xx", null), "orders"),
                repo(config("ds_x", "mysql", "{\"username\":\"ai_ro\",\"passwordEnv\":\"" + present + "\"}")));

        SourceHealth source = service.checkAll().get(0);

        Assert.assertEquals("规则 1 不命中时应落到 jdbcUrl 规则", "conn_config 缺少 jdbcUrl", source.getHint());
    }

    /**
     * M4 §6.1 记录的规则空档补齐：连 passwordEnv 键都没配时，要说清是"配置缺键"，
     * 而不是含糊地回 null（那种情况下 MysqlOrderProvider 会按空密码连接，必然失败）。
     */
    @Test
    public void mysql_missingPasswordEnvKey_hintPointsAtMissingKey() {
        DataSourceHealthService service = service(
                provider("ds_noenv", "mysql", HealthStatus.down("健康检查失败 dsId=ds_noenv：xx", null), "orders"),
                repo(config("ds_noenv", "mysql", MYSQL_CONN_PREFIX + "}")));

        SourceHealth source = service.checkAll().get(0);

        Assert.assertNotNull("缺 passwordEnv 键时必须给出 hint", source.getHint());
        Assert.assertTrue("应指出缺少 passwordEnv：" + source.getHint(), source.getHint().contains("缺少 passwordEnv"));
    }

    /** 空串 passwordEnv 与"键不存在"等价：同样要指出来，而不是回 null */
    @Test
    public void mysql_blankPasswordEnv_hintPointsAtMissingKey() {
        DataSourceHealthService service = service(
                provider("ds_blankenv", "mysql", HealthStatus.down("健康检查失败 dsId=ds_blankenv：xx", null), "orders"),
                repo(config("ds_blankenv", "mysql", MYSQL_CONN_PREFIX + ",\"passwordEnv\":\"\"}")));

        SourceHealth source = service.checkAll().get(0);

        Assert.assertNotNull("passwordEnv 为空串时也必须给出 hint", source.getHint());
        Assert.assertTrue("空串应被当作缺失：" + source.getHint(), source.getHint().contains("缺少 passwordEnv"));
    }

    /**
     * P1-2：规则 1/2 已从"仅 mysql"放宽到"任何 JDBC + passwordEnv 类型"——
     * 新增 postgres 数据源时，同样的缺密码问题不能只回 null。
     */
    @Test
    public void postgres_missingPasswordEnvVar_hintNamesThatVariable() {
        DataSourceHealthService service = service(
                provider("ds_pg_payment", "postgres", HealthStatus.down("健康检查失败 dsId=ds_pg_payment：xx", null), "payments"),
                repo(config("ds_pg_payment", "postgres",
                        PG_CONN_PREFIX + ",\"passwordEnv\":\"" + MISSING_ENV + "\"}")));

        SourceHealth source = service.checkAll().get(0);

        Assert.assertTrue("postgres 源也要给出环境变量名：" + source.getHint(),
                source.getHint() != null && source.getHint().contains(MISSING_ENV));
    }

    /** postgres 的 jdbcUrl 缺失同样要能指出来 */
    @Test
    public void postgres_missingJdbcUrl_hintIsJdbcUrl() {
        String present = presentEnvName();
        DataSourceHealthService service = service(
                provider("ds_pg_payment", "postgres", HealthStatus.down("健康检查失败 dsId=ds_pg_payment：xx", null), "payments"),
                repo(config("ds_pg_payment", "postgres", "{\"username\":\"postgres\",\"passwordEnv\":\"" + present + "\"}")));

        Assert.assertEquals("conn_config 缺少 jdbcUrl", service.checkAll().get(0).getHint());
    }

    @Test
    public void elasticsearch_missingHost_hintIsHost() {
        DataSourceHealthService service = service(
                provider("ds_log_es", "elasticsearch", HealthStatus.down("健康检查失败 dsId=ds_log_es：xx", null), "logs"),
                repo(config("ds_log_es", "elasticsearch", "{}")));

        Assert.assertEquals("conn_config 缺少 host", service.checkAll().get(0).getHint());
    }

    @Test
    public void unknownReason_hintIsNull_noFabricatedExplanation() {
        String present = presentEnvName();
        DataSourceHealthService service = service(
                provider("ds_y", "mysql", HealthStatus.down("健康检查失败 dsId=ds_y：some other failure", null), "orders"),
                repo(config("ds_y", "mysql",
                        MYSQL_CONN_PREFIX + ",\"passwordEnv\":\"" + present + "\"}")));

        SourceHealth source = service.checkAll().get(0);

        Assert.assertFalse(source.isHealthy());
        Assert.assertNull("无据可依时不得编造 hint", source.getHint());
        Assert.assertTrue(source.getMessage().contains("some other failure"));
    }

    // ------------------------------------------------------------------ 健康/不健康映射

    @Test
    public void healthySource_hasNoMessageNoHint_keepsCostAndEntities() {
        HealthStatus up = HealthStatus.up();
        up.setCostMs(7L);
        DataSourceHealthService service = service(
                provider("ds_order_mysql", "mysql", up, "orders"),
                repo(config("ds_order_mysql", "mysql", MYSQL_CONN_PREFIX + "}")));

        SourceHealth source = service.checkAll().get(0);

        Assert.assertTrue(source.isHealthy());
        Assert.assertNull("健康时 message 应为 null", source.getMessage());
        Assert.assertNull("健康时 hint 应为 null", source.getHint());
        Assert.assertEquals(7L, source.getCostMs());
        Assert.assertEquals(1, source.getEntities().size());
        Assert.assertEquals("orders", source.getEntities().get(0).getEntityCode());
        Assert.assertEquals("entity_query", source.getEntities().get(0).getCapability());
    }

    @Test
    public void providerThrowing_isCapturedAsUnhealthy_doesNotBreakRound() {
        DataSourceHealthService service = service(
                new IDataSourceProvider() {
                    @Override
                    public String dsId() {
                        return "ds_boom";
                    }

                    @Override
                    public String dsType() {
                        return "mysql";
                    }

                    @Override
                    public Set<DataSourceCapability> capabilities() {
                        return Set.of();
                    }

                    @Override
                    public HealthStatus healthCheck() {
                        throw new IllegalStateException("boom");
                    }

                    @Override
                    public List<EntityMeta> listEntities() {
                        return List.of();
                    }
                },
                repo(config("ds_boom", "mysql", MYSQL_CONN_PREFIX + "}")));

        SourceHealth source = service.checkAll().get(0);
        Assert.assertFalse(source.isHealthy());
        Assert.assertTrue(source.getMessage().contains("健康检查异常"));
        Assert.assertTrue(source.getMessage().contains("boom"));
    }

    @Test
    public void costMsNotReportedByProvider_isMeasuredLocally() {
        DataSourceHealthService service = service(
                provider("ds_slow", "mysql", HealthStatus.down("健康检查失败 dsId=ds_slow：slow", null), "orders"),
                repo(config("ds_slow", "mysql", MYSQL_CONN_PREFIX + "}")));

        Assert.assertTrue("Provider 未报 costMs 时服务应自己测", service.checkAll().get(0).getCostMs() >= 0);
    }

    // ------------------------------------------------------------------ 缓存语义

    @Test
    public void cache_hitWithinTtl_refreshBypasses_refreshRewritesCache() {
        CountingProvider provider = new CountingProvider("ds_c", "mysql", HealthStatus.up());
        DataSourceHealthService service = service(provider, repo(config("ds_c", "mysql", MYSQL_CONN_PREFIX + "}")));

        Snapshot first = service.health(false);
        Assert.assertFalse("首次必然实查", first.isCached());
        Assert.assertEquals(1, provider.calls.get());

        Snapshot second = service.health(false);
        Assert.assertTrue("TTL 内应命中缓存", second.isCached());
        Assert.assertEquals("TTL 内不得再打数据源", 1, provider.calls.get());
        Assert.assertEquals("命中的是同一份快照", first.getCheckedAt(), second.getCheckedAt());

        Snapshot forced = service.health(true);
        Assert.assertFalse("refresh=true 必须绕过缓存", forced.isCached());
        Assert.assertEquals(2, provider.calls.get());

        Snapshot afterForced = service.health(false);
        Assert.assertTrue("refresh 应回写缓存", afterForced.isCached());
        Assert.assertEquals("回写后不应再多打一次", 2, provider.calls.get());
        Assert.assertEquals(forced.getCheckedAt(), afterForced.getCheckedAt());
    }

    @Test
    public void invalidate_forcesRecheckOnNextCall() {
        CountingProvider provider = new CountingProvider("ds_c", "mysql", HealthStatus.up());
        DataSourceHealthService service = service(provider, repo(config("ds_c", "mysql", MYSQL_CONN_PREFIX + "}")));

        service.health(false);
        Assert.assertTrue(service.health(false).isCached());

        service.invalidate();
        Snapshot afterInvalidate = service.health(false);
        Assert.assertFalse("失效后必须重新检查（热更新场景）", afterInvalidate.isCached());
        Assert.assertEquals(2, provider.calls.get());
    }

    @Test
    public void cache_expiresAfterTtl() throws Exception {
        CountingProvider provider = new CountingProvider("ds_c", "mysql", HealthStatus.up());
        DataSourceHealthService service = service(provider, repo(config("ds_c", "mysql", MYSQL_CONN_PREFIX + "}")));
        service.setTtlMs(1L);

        Assert.assertFalse(service.health(false).isCached());
        Thread.sleep(20L);
        Snapshot expired = service.health(false);
        Assert.assertFalse("TTL 过期后应重新检查", expired.isCached());
        Assert.assertEquals(2, provider.calls.get());
    }

    @Test
    public void logReport_writesCache_andSurvivesEmptyRegistry() {
        CountingProvider provider = new CountingProvider("ds_c", "mysql", HealthStatus.up());
        DataSourceHealthService service = service(provider, repo(config("ds_c", "mysql", MYSQL_CONN_PREFIX + "}")));

        service.logReport();
        Assert.assertEquals("logReport 应实查一次", 1, provider.calls.get());
        Assert.assertTrue("logReport 应回写缓存，端点随后可直接复用", service.health(false).isCached());

        // 空注册表：只告警，不抛异常（启动期不能因为没 Provider 就炸）
        DataSourceHealthService empty = service(new IDataSourceProvider[0], repo());
        Assert.assertTrue(empty.checkAll().isEmpty());
        empty.logReport();
    }

    @Test
    public void checkAll_isSortedByDsId_forStableOutput() {
        DataSourceHealthService service = service(
                provider("ds_zzz", "mysql", HealthStatus.up(), "orders"),
                provider("ds_aaa", "mysql", HealthStatus.up(), "orders"),
                repo(config("ds_zzz", "mysql", MYSQL_CONN_PREFIX + "}"),
                        config("ds_aaa", "mysql", MYSQL_CONN_PREFIX + "}")));

        List<SourceHealth> sources = service.checkAll();
        Assert.assertEquals(2, sources.size());
        Assert.assertEquals("ds_aaa", sources.get(0).getDsId());
        Assert.assertEquals("ds_zzz", sources.get(1).getDsId());
    }

    // ------------------------------------------------------------------ 测试脚手架

    /**
     * 组装一个只装了 registry/repository 的服务实例（字段注入，用反射补齐）
     */
    private DataSourceHealthService service(IDataSourceProvider provider, IDataSourceRepository repository) {
        return service(provider == null ? new IDataSourceProvider[0] : new IDataSourceProvider[]{provider}, repository);
    }

    private DataSourceHealthService service(IDataSourceProvider providerA, IDataSourceProvider providerB,
                                            IDataSourceRepository repository) {
        return service(new IDataSourceProvider[]{providerA, providerB}, repository);
    }

    private DataSourceHealthService service(IDataSourceProvider[] providers, IDataSourceRepository repository) {
        DataSourceHealthService service = new DataSourceHealthService();
        inject(service, "dataSourceRegistry", registry(providers));
        inject(service, "dataSourceRepository", repository);
        return service;
    }

    private IDataSourceProvider provider(String dsId, String dsType, HealthStatus status, String entityCode) {
        return new IDataSourceProvider() {
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
                return "mysql".equals(dsType)
                        ? Set.of(DataSourceCapability.ENTITY_META, DataSourceCapability.ENTITY_QUERY)
                        : Set.of(DataSourceCapability.LOG_SEARCH);
            }

            @Override
            public HealthStatus healthCheck() {
                return status;
            }

            @Override
            public List<EntityMeta> listEntities() {
                return List.of(EntityMeta.builder()
                        .dsId(dsId).entityCode(entityCode).entityName("订单")
                        .capability("mysql".equals(dsType) ? "entity_query" : "log_search")
                        .build());
            }
        };
    }

    private IDataSourceRegistry registry(IDataSourceProvider... providers) {
        return new IDataSourceRegistry() {
            @Override
            public void register(IDataSourceProvider provider) {
                throw new UnsupportedOperationException("本测试不验证注册");
            }

            @Override
            public void unregister(String dsId) {
                throw new UnsupportedOperationException("本测试不验证注销");
            }

            @Override
            public java.util.Optional<IDataSourceProvider> get(String dsId) {
                return java.util.Arrays.stream(providers)
                        .filter(p -> p.dsId().equals(dsId)).findFirst();
            }

            @Override
            public List<IDataSourceProvider> listByTenant(String tenantId) {
                return new ArrayList<>(List.of(providers));
            }
        };
    }

    private IDataSourceRepository repo(IDataSourceRepository.DataSourceConfig... configs) {
        Map<String, IDataSourceRepository.DataSourceConfig> map = new LinkedHashMap<>();
        for (IDataSourceRepository.DataSourceConfig config : configs) {
            map.put(config.getDsId(), config);
        }
        return new IDataSourceRepository() {
            @Override
            public Map<String, DataSourceConfig> queryEnabledDataSources() {
                return map;
            }

            @Override
            public Map<String, DataSourceConfig> queryEnabledDataSources(String tenantId) {
                return map;
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

    private IDataSourceRepository.DataSourceConfig config(String dsId, String dsType, String connConfig) {
        return IDataSourceRepository.DataSourceConfig.builder()
                .dsId(dsId).dsType(dsType).connConfig(connConfig).build();
    }

    /**
     * 取一个**确实存在**的环境变量名，用于验证"变量存在 → 规则 1 不命中"
     */
    private String presentEnvName() {
        for (String name : new String[]{"JAVA_HOME", "PATH", "USERPROFILE", "OS"}) {
            if (System.getenv(name) != null) {
                return name;
            }
        }
        throw new IllegalStateException("测试进程没有任何可用的环境变量，无法验证\"变量存在\"分支");
    }

    private void inject(Object target, String field, Object value) {
        try {
            Field f = target.getClass().getDeclaredField(field);
            f.setAccessible(true);
            f.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("注入测试桩失败：" + field, e);
        }
    }

    /**
     * 计数 Provider：验证"缓存命中时不得再打数据源"
     */
    private static class CountingProvider implements IDataSourceProvider {

        private final String dsId;

        private final String dsType;

        private final HealthStatus status;

        private final AtomicInteger calls = new AtomicInteger();

        private CountingProvider(String dsId, String dsType, HealthStatus status) {
            this.dsId = dsId;
            this.dsType = dsType;
            this.status = status;
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
            return Set.of(DataSourceCapability.ENTITY_QUERY);
        }

        @Override
        public HealthStatus healthCheck() {
            calls.incrementAndGet();
            return status;
        }

        @Override
        public List<EntityMeta> listEntities() {
            return List.of();
        }
    }

}
