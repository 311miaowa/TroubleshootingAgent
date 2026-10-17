package cn.bugstack.ai.domain.agent.adapter.port.datasource;

import cn.bugstack.ai.domain.agent.model.valobj.datasource.EntityQuery;
import cn.bugstack.ai.domain.agent.model.valobj.datasource.QueryResult;

/**
 * 实体结构化查询 SPI
 *
 * @author miaowazhongzi
 * 2025/6/27 18:29
 */
public interface IEntityQueryProvider extends IDataSourceProvider, AutoCloseable {

    /**
     * 执行结构化查询
     * <p>
     * 查询语句只能来自实体配置中的 {@code query_template}，值走参数绑定。
     */
    QueryResult query(EntityQuery query);

}
