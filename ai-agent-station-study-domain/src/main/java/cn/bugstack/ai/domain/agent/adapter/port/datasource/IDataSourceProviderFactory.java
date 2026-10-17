package cn.bugstack.ai.domain.agent.adapter.port.datasource;

import cn.bugstack.ai.domain.agent.adapter.port.datasource.IDataSourceRegistry;
import cn.bugstack.ai.domain.agent.adapter.repository.IDataSourceRepository;

/**
 * 数据源 Provider 工厂（P2 收敛：把注册器里的 {@code switch(dsType)} 换成注册表）
 * <p>
 * <b>为什么原来不好</b>：{@code DataSourceProviderRegistrar} 用 {@code switch} 硬编码了
 * {@code elasticsearch/mysql/postgres} 三种类型，且每次都 {@code new} 具体实现类——
 * 结果是"新增数据源要改注册器"，与文档里"新增数据源只加一个类"的说法不符。
 * <p>
 * <b>现在怎么做</b>：每种数据源提供一个 {@code @Component} 实现本接口并声明 {@link #dsType()}，
 * 注册器只按类型查表分发，**新增数据源不再改动注册器**。
 * <p>
 * <b>实例生命周期</b>：{@link #create} 每次调用都返回**新实例**（注册器以覆盖方式注册，
 * 旧实例由 {@link IDataSourceRegistry#register} 负责关闭），这与热更新链路的要求一致。
 *
 * @author miaowazhongzi
 */
public interface IDataSourceProviderFactory {

    /**
     * 该工厂负责的 {@code ai_data_source.ds_type}（如 {@code elasticsearch} / {@code mysql} / {@code postgres}）
     */
    String dsType();

    /**
     * 按配置创建 Provider 实例（未建立连接，连接池由实现类懒加载）
     *
     * @param config     数据源配置（含 dsId / conn_config）
     * @param repository 仓储，供实现类按需读取实体元数据
     */
    IDataSourceProvider create(IDataSourceRepository.DataSourceConfig config, IDataSourceRepository repository);

}
