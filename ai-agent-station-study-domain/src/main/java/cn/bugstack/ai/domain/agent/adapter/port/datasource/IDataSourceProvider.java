package cn.bugstack.ai.domain.agent.adapter.port.datasource;

import cn.bugstack.ai.domain.agent.model.valobj.datasource.EntityMeta;
import cn.bugstack.ai.domain.agent.model.valobj.datasource.HealthStatus;

import java.util.List;
import java.util.Set;

/**
 * 数据源 Provider 基础 SPI
 * <p>
 * 所有数据源实现（ES / MySQL / 后续 Loki、ClickHouse）都必须实现本接口；
 * 具备查询能力时再叠加 {@link IEntityQueryProvider} 或 {@link ILogSearchProvider}。
 * <p>
 * <b>纪律</b>：每个 Provider 自带行数上限与超时（读 {@code ai_data_source.max_rows} /
 * {@code timeout_ms}），不靠调用方自觉。
 *
 * @author miaowazhongzi
 * 2025/6/27 18:29
 */
public interface IDataSourceProvider {

    /**
     * 数据源ID（对应 ai_data_source.ds_id）
     */
    String dsId();

    /**
     * 数据源类型（mysql / elasticsearch / loki / clickhouse）
     */
    String dsType();

    /**
     * 本 Provider 支持的能力集合
     */
    Set<DataSourceCapability> capabilities();

    /**
     * 健康检查
     */
    HealthStatus healthCheck();

    /**
     * 列出本数据源下的实体元数据
     */
    List<EntityMeta> listEntities();

}
