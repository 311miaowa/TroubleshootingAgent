package cn.bugstack.ai.domain.agent.adapter.port.datasource;

import cn.bugstack.ai.domain.agent.model.valobj.datasource.LogSearchQuery;
import cn.bugstack.ai.domain.agent.model.valobj.datasource.QueryResult;

/**
 * 日志检索 SPI
 *
 * @author miaowazhongzi
 * 2025/6/27 18:29
 */
public interface ILogSearchProvider extends IDataSourceProvider, AutoCloseable {

    /**
     * 执行日志检索
     */
    QueryResult search(LogSearchQuery query);

}
