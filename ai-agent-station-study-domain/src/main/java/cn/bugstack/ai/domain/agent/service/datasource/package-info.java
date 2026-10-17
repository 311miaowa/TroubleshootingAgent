/**
 * 统一数据源领域服务包
 * <p>
 * M1.0 只落地 {@link cn.bugstack.ai.domain.agent.service.datasource.DataSourceRegistry}；
 * {@code DataSourceQueryService}（选源 → 注入 tenantId → 调用 → 记 trace）与
 * {@code tool/DataSourceToolCallbackFactory}（按阶段裁剪语义工具）在 M1.1 加入。
 *
 * @author miaowazhongzi
 */
package cn.bugstack.ai.domain.agent.service.datasource;
