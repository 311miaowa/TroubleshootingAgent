package cn.bugstack.ai.test.p2;

import cn.bugstack.ai.domain.agent.adapter.port.datasource.IDataSourceProvider;
import cn.bugstack.ai.domain.agent.adapter.port.datasource.IDataSourceProviderFactory;
import cn.bugstack.ai.domain.agent.adapter.repository.IDataSourceRepository;
import cn.bugstack.ai.infrastructure.gateway.datasource.DataSourceProviderRegistrar;
import cn.bugstack.ai.infrastructure.gateway.datasource.ElasticsearchProvider;
import cn.bugstack.ai.infrastructure.gateway.datasource.ElasticsearchProviderFactory;
import cn.bugstack.ai.infrastructure.gateway.datasource.MysqlOrderProvider;
import cn.bugstack.ai.infrastructure.gateway.datasource.MysqlOrderProviderFactory;
import cn.bugstack.ai.infrastructure.gateway.datasource.PostgresEntityProvider;
import cn.bugstack.ai.infrastructure.gateway.datasource.PostgresEntityProviderFactory;
import org.junit.Assert;
import org.junit.Test;

import java.util.Arrays;
import java.util.Map;

/**
 * P2 注册器收敛单测：{@code switch(dsType)} → 工厂查找表
 * <p>
 * 覆盖三件事：①不同 dsType 各自可查；②同名 dsType 后者覆盖前者（并 WARN，说明有两个实现抢类型）；
 * ③未声明类型的工厂被忽略（而不是把 null 塞进表里）。
 *
 * @author miaowazhongzi
 */
public class ProviderFactoryRegistryTest {

    @Test
    public void indexesDistinctTypes() {
        Map<String, IDataSourceProviderFactory> index = DataSourceProviderRegistrar.buildFactoryIndex(
                Arrays.asList(fake("mysql"), fake("postgres"), fake("elasticsearch")));

        Assert.assertEquals(3, index.size());
        Assert.assertNotNull(index.get("mysql"));
        Assert.assertNotNull(index.get("postgres"));
        Assert.assertNotNull(index.get("elasticsearch"));
    }

    @Test
    public void duplicateType_lastWins() {
        IDataSourceProviderFactory first = fake("mysql", "first");
        IDataSourceProviderFactory second = fake("mysql", "second");

        Map<String, IDataSourceProviderFactory> index = DataSourceProviderRegistrar.buildFactoryIndex(
                Arrays.asList(first, second));

        Assert.assertEquals("同名类型应只保留一个：", 1, index.size());
        Assert.assertSame("后者应覆盖前者：", second, index.get("mysql"));
    }

    @Test
    public void skipsFactoriesWithoutType() {
        Map<String, IDataSourceProviderFactory> index = DataSourceProviderRegistrar.buildFactoryIndex(
                Arrays.asList(fake(""), fake(null), null, fake("mysql")));

        Assert.assertEquals("只有声明了 dsType 的工厂能进表：", 1, index.size());
        Assert.assertTrue(index.containsKey("mysql"));
    }

    @Test
    public void indexIsPureTypeIndex_noWhitelist() {
        // buildFactoryIndex 只做"按 dsType 建索引"，不校验类型白名单：
        // 未知类型（如 mongodb）照样进表；真正跳过发生在 refresh() 里（查表 miss → WARN）
        Map<String, IDataSourceProviderFactory> index = DataSourceProviderRegistrar.buildFactoryIndex(
                Arrays.asList(fake("mongodb")));

        Assert.assertTrue("未知类型也应进索引（本方法是纯索引，不是白名单）：", index.containsKey("mongodb"));
        Assert.assertNull("没进表的类型查出来才是 null：", index.get("clickhouse"));
    }

    /**
     * 三个真实工厂必须各自造出**对应实现类** —— 防的是复制粘贴时把
     * {@code new MysqlOrderProvider} 写进 Postgres 工厂（编译通过、启动日志也照样"注册成功"，
     * 最后表现为方言错误这种极难定位的故障）。
     */
    @Test
    public void realFactoriesCreateMatchingProviders() {
        IDataSourceRepository repository = repositoryStub();

        Assert.assertTrue("elasticsearch 工厂必须造 ElasticsearchProvider",
                new ElasticsearchProviderFactory()
                        .create(config("elasticsearch"), repository) instanceof ElasticsearchProvider);
        Assert.assertTrue("mysql 工厂必须造 MysqlOrderProvider",
                new MysqlOrderProviderFactory()
                        .create(config("mysql"), repository) instanceof MysqlOrderProvider);
        Assert.assertTrue("postgres 工厂必须造 PostgresEntityProvider",
                new PostgresEntityProviderFactory()
                        .create(config("postgres"), repository) instanceof PostgresEntityProvider);
    }

    @Test
    public void factoryDsTypeMatchesProviderDsType() {
        Assert.assertEquals(ElasticsearchProvider.DS_TYPE, new ElasticsearchProviderFactory().dsType());
        Assert.assertEquals(MysqlOrderProvider.DS_TYPE, new MysqlOrderProviderFactory().dsType());
        Assert.assertEquals(PostgresEntityProvider.DS_TYPE, new PostgresEntityProviderFactory().dsType());
    }

    // ------------------------------------------------------------------ 夹具

    private static IDataSourceRepository.DataSourceConfig config(String dsType) {
        return IDataSourceRepository.DataSourceConfig.builder()
                .dsId("ds_" + dsType)
                .dsName(dsType)
                .dsType(dsType)
                .connConfig("{}")
                .build();
    }

    /** 三个 Provider 的构造器只赋值/建 HttpClient，不建连接，因此空桩足够 */
    private static IDataSourceRepository repositoryStub() {
        return (IDataSourceRepository) java.lang.reflect.Proxy.newProxyInstance(
                ProviderFactoryRegistryTest.class.getClassLoader(),
                new Class<?>[]{IDataSourceRepository.class},
                (proxy, method, args) -> null);
    }

    @Test
    public void nullListYieldsEmptyIndex() {
        Assert.assertTrue(DataSourceProviderRegistrar.buildFactoryIndex(null).isEmpty());
    }

    // ------------------------------------------------------------------ 夹具

    private static IDataSourceProviderFactory fake(String dsType) {
        return fake(dsType, dsType);
    }

    /** name 只用于断言"后者覆盖前者"时区分实例 */
    private static IDataSourceProviderFactory fake(String dsType, String name) {
        return new IDataSourceProviderFactory() {
            @Override
            public String dsType() {
                return dsType;
            }

            @Override
            public IDataSourceProvider create(IDataSourceRepository.DataSourceConfig config,
                                              IDataSourceRepository repository) {
                return null;
            }

            @Override
            public String toString() {
                return "fake-" + name;
            }
        };
    }

}
