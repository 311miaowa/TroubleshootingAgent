package cn.bugstack.ai.domain.agent.service.datasource;

import cn.bugstack.ai.domain.agent.adapter.port.datasource.IDataSourceProvider;
import cn.bugstack.ai.domain.agent.adapter.port.datasource.IDataSourceRegistry;
import cn.bugstack.ai.domain.agent.adapter.port.datasource.IEntityQueryProvider;
import cn.bugstack.ai.domain.agent.adapter.port.datasource.ILogSearchProvider;
import cn.bugstack.ai.domain.agent.model.valobj.datasource.EntityMeta;
import cn.bugstack.ai.domain.agent.model.valobj.datasource.EntityQuery;
import cn.bugstack.ai.domain.agent.model.valobj.datasource.LogSearchQuery;
import cn.bugstack.ai.domain.agent.model.valobj.datasource.QueryResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;
import java.util.List;

/**
 * 统一数据源查询门面
 * <p>
 * 职责：选源 → 注入 tenantId → 调用 Provider → 记录 trace（耗时）。
 * 它是模型语义工具与具体 Provider 之间的唯一入口，模型永远不直接接触 Provider。
 * <p>
 * <b>纪律</b>：{@code tenantId} 由本服务从会话上下文注入，绝不出现在工具 schema 里。
 * M1 阶段尚无会话级租户上下文（M5 才做多租户），此处先注入固定值 {@code default}
 * （与 ai_data_source.tenant_id 种子一致），待 M5 换成真实上下文。
 *
 * @author miaowazhongzi
 * 2025/6/27 18:29
 */
@Slf4j
@Service
public class DataSourceQueryService {

    /**
     * M1 阶段的默认租户；M5 多租户落地后由会话上下文注入替换
     */
    public static final String DEFAULT_TENANT_ID = "default";

    @Resource
    private IDataSourceRegistry dataSourceRegistry;

    /**
     * 日志检索：按 dsId 路由到日志 Provider
     */
    public QueryResult search(String dsId, LogSearchQuery query) {
        IDataSourceProvider provider = requireProvider(dsId);
        if (!(provider instanceof ILogSearchProvider logSearchProvider)) {
            throw new IllegalArgumentException("数据源不支持日志检索能力：dsId=" + dsId
                    + "，实际类型=" + provider.getClass().getSimpleName());
        }

        // 租户由框架注入，模型无感
        query.setTenantId(DEFAULT_TENANT_ID);

        long start = System.currentTimeMillis();
        try {
            QueryResult result = logSearchProvider.search(query);
            long cost = System.currentTimeMillis() - start;
            result.setCostMs(result.getCostMs() > 0 ? result.getCostMs() : cost);
            log.info("数据源查询完成 dsId={} entityCode={} rows={} truncated={} costMs={}",
                    result.getDsId(), result.getEntityCode(), result.getRows() == null ? 0 : result.getRows().size(),
                    result.isTruncated(), result.getCostMs());
            return result;
        } catch (Exception e) {
            long cost = System.currentTimeMillis() - start;
            log.error("数据源查询失败 dsId={} costMs={}：{}", dsId, cost, e.getMessage(), e);
            QueryResult result = QueryResult.empty(dsId, query == null ? null : null);
            result.getWarnings().add("查询失败: " + e.getMessage());
            result.setCostMs(cost);
            return result;
        }
    }

    /**
     * 实体元数据：按 dsId 列出实体（供 describe_entity 语义工具使用）
     */
    public List<EntityMeta> describeEntities(String dsId) {
        IDataSourceProvider provider = requireProvider(dsId);
        return provider.listEntities();
    }

    /**
     * 实体结构化查询：按 dsId 路由到实体查询 Provider（M1.3，MySQL 订单源）
     */
    public QueryResult query(String dsId, EntityQuery query) {
        IDataSourceProvider provider = requireProvider(dsId);
        if (!(provider instanceof IEntityQueryProvider entityQueryProvider)) {
            throw new IllegalArgumentException("数据源不支持实体查询能力：dsId=" + dsId
                    + "，实际类型=" + provider.getClass().getSimpleName());
        }

        // 租户由框架注入，模型无感
        query.setTenantId(DEFAULT_TENANT_ID);

        long start = System.currentTimeMillis();
        try {
            QueryResult result = entityQueryProvider.query(query);
            long cost = System.currentTimeMillis() - start;
            result.setCostMs(result.getCostMs() > 0 ? result.getCostMs() : cost);
            log.info("数据源查询完成 dsId={} entityCode={} rows={} truncated={} costMs={}",
                    result.getDsId(), result.getEntityCode(), result.getRows() == null ? 0 : result.getRows().size(),
                    result.isTruncated(), result.getCostMs());
            return result;
        } catch (Exception e) {
            long cost = System.currentTimeMillis() - start;
            log.error("数据源查询失败 dsId={} costMs={}：{}", dsId, cost, e.getMessage(), e);
            QueryResult result = QueryResult.empty(dsId, query == null ? null : query.getEntityCode());
            result.getWarnings().add("查询失败: " + e.getMessage());
            result.setCostMs(cost);
            return result;
        }
    }

    private IDataSourceProvider requireProvider(String dsId) {
        return dataSourceRegistry.get(dsId)
                .orElseThrow(() -> new IllegalArgumentException("数据源未注册：dsId=" + dsId));
    }

}
