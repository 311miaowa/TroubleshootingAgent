package cn.bugstack.ai.domain.agent.adapter.port.datasource;

import java.util.List;
import java.util.Optional;

/**
 * 数据源注册表 SPI
 * <p>
 * 与 {@link IDataSourceProvider} 同属"端口"：调用方（{@code DataSourceQueryService}、
 * 工具工厂）只依赖本接口，不关心注册表是怎么实现的、Provider 是何时注册进来的。
 *
 * @author miaowazhongzi
 * 2025/6/27 18:29
 */
public interface IDataSourceRegistry {

    /**
     * 注册（同 dsId 覆盖）
     */
    void register(IDataSourceProvider provider);

    /**
     * 注销
     */
    void unregister(String dsId);

    /**
     * 按 dsId 取
     */
    Optional<IDataSourceProvider> get(String dsId);

    /**
     * 按租户列出可用数据源
     */
    List<IDataSourceProvider> listByTenant(String tenantId);

}
