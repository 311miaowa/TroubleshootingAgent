package cn.bugstack.ai.domain.agent.adapter.repository;

import cn.bugstack.ai.domain.agent.model.valobj.datasource.EntityMeta;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * 统一数据源配置仓储接口
 * <p>
 * 只负责"把配置读出来"，不负责查询数据、也不负责装配 Provider——
 * 后者是 {@code IDataSourceProvider} 与 {@code DataSourceRegistry} 的职责。
 * <p>
 * <b>所有查询默认只返回 status=1 的记录</b>：字段字典表与实体表都带 status，
 * 若 DAO 层漏了这层过滤，被停用的实体/字段会照样暴露给模型。
 *
 * @author miaowazhongzi
 * 2025/6/27 18:29
 */
public interface IDataSourceRepository {

    /**
     * 查询全部启用的数据源配置（key = dsId）
     */
    Map<String, DataSourceConfig> queryEnabledDataSources();

    /**
     * 按租户查询启用的数据源配置（key = dsId）
     */
    Map<String, DataSourceConfig> queryEnabledDataSources(String tenantId);

    /**
     * 查询指定数据源下启用的实体元数据（已装配字段字典）
     *
     * @param dsId 数据源ID
     */
    List<EntityMeta> queryEnabledEntities(String dsId);

    /**
     * 查询指定数据源下启用的字段字典，key = entityCode
     *
     * @param dsId 数据源ID
     */
    Map<String, List<EntityMeta.FieldMeta>> queryEnabledFields(String dsId);

    /**
     * 查询指定数据源下启用的实体执行配置（含 capability / query_template，供 Provider 使用）
     * <p>
     * 与 {@link #queryEnabledEntities(String)} 的区别：后者是"给模型看的元数据"（不含查询模板），
     * 本方法返回"给 Provider 执行用的完整配置"（含物理定位与参数化查询模板）。
     *
     * @param dsId 数据源ID
     */
    List<EntityConfig> queryEnabledEntityConfigs(String dsId);

    /**
     * 查询全部阶段-能力策略，key = stageCode，value = 允许的能力列表
     */
    Map<String, List<String>> queryStageToolPolicy();

    /**
     * 查询指定阶段允许的能力白名单
     *
     * @param stageCode 阶段编码（等于 ai_agent_flow_config.client_type）
     */
    List<String> queryAllowedCapabilities(String stageCode);

    /**
     * 数据源配置聚合（由 ai_data_source 一行装配而成）
     */
    @Data
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    class DataSourceConfig {

        /**
         * 数据源ID
         */
        private String dsId;

        /**
         * 数据源名称
         */
        private String dsName;

        /**
         * 类型(mysql/elasticsearch/loki/clickhouse)
         */
        private String dsType;

        /**
         * 租户标识
         */
        private String tenantId;

        /**
         * 经MCP接入时对应的 mcp_id（如 5007）
         */
        private String mcpId;

        /**
         * 连接配置(JSON,不含明文密码)
         */
        private String connConfig;

        /**
         * 默认时间字段
         */
        private String timeField;

        /**
         * 单次结果行数硬上限
         */
        private int maxRows;

        /**
         * 查询超时(毫秒)
         */
        private int timeoutMs;

        /**
         * 状态(0:禁用,1:启用)
         */
        private Integer status;

    }

    /**
     * 实体执行配置聚合（ai_data_source_entity 一行 + 数据源级 time_field 装配而成）
     * <p>
     * 供 Provider 渲染查询使用，<b>不暴露给模型</b>（模型只看到 {@link EntityMeta}）。
     */
    @Data
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    class EntityConfig {

        /**
         * 数据源ID
         */
        private String dsId;

        /**
         * 实体编码
         */
        private String entityCode;

        /**
         * 实体名称
         */
        private String entityName;

        /**
         * 实体描述
         */
        private String entityDesc;

        /**
         * 能力(entity_query/log_search)
         */
        private String capability;

        /**
         * 物理定位(表名/索引模式)
         */
        private String physicalName;

        /**
         * 参数化查询模板
         */
        private String queryTemplate;

        /**
         * 时间字段（字段字典 is_time_field=1 优先，否则数据源级 time_field）
         */
        private String timeField;

    }

}
