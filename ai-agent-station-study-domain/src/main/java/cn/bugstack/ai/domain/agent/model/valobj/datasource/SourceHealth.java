package cn.bugstack.ai.domain.agent.model.valobj.datasource;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * 单个数据源的健康快照，值对象（M4）
 * <p>
 * 它是「数据源是否真的可用」这一事实的<b>对外表达</b>：启动期 WARN 日志、健康端点、
 * 前端 Data Sources 面板三者共用同一个结构，避免"日志说一套、UI 说另一套"。
 * <p>
 * <b>与 {@link HealthStatus} 的分工</b>：{@code HealthStatus} 是 Provider 的自述（只有
 * healthy/message/costMs）；本类是<b>权威视图</b>——额外带上 {@code dsId/dsType/entities}
 * 这些由注册表与配置表提供的上下文，以及 {@code hint}（可操作提示）。
 *
 * @author miaowazhongzi
 * 2025/6/27 18:29
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class SourceHealth {

    /**
     * 数据源ID（对应 ai_data_source.ds_id）
     */
    private String dsId;

    /**
     * 数据源类型（mysql / elasticsearch / ...）
     */
    private String dsType;

    /**
     * 是否健康
     */
    private boolean healthy;

    /**
     * 不健康时的原始原因（来自 Provider 的 {@code healthCheck()}）；健康时为 null
     */
    private String message;

    /**
     * 本次健康检查耗时（毫秒）；失败时同样记录，便于与连接超时（如 Hikari connectionTimeout）对照
     */
    private long costMs;

    /**
     * 可操作提示：由 conn_config 推导（如"缺哪个环境变量"）；无据可依时为 null，不编造解释
     */
    private String hint;

    /**
     * 该数据源对外提供的实体与能力（来自 Provider 的 {@code listEntities()}）
     */
    @Builder.Default
    private List<EntityBrief> entities = new ArrayList<>();

    /**
     * 实体摘要：只保留"有哪些实体、叫什么、能干什么"，不含字段字典
     * （字段字典是 {@code describe_entity} 的职责，健康面板不需要）
     */
    @Data
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    public static class EntityBrief {

        /**
         * 实体编码
         */
        private String entityCode;

        /**
         * 实体名称
         */
        private String entityName;

        /**
         * 能力(entity_query/log_search)
         */
        private String capability;

    }

}
