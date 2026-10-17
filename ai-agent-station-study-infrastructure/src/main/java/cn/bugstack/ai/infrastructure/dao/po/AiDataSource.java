package cn.bugstack.ai.infrastructure.dao.po;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 数据源配置表
 *
 * @author miaowazhongzi
 * @description 数据源配置表 PO 对象
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class AiDataSource {

    /**
     * 主键ID
     */
    private Long id;

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
     * 经MCP接入时对应的ai_client_tool_mcp.mcp_id
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
    private Integer maxRows;

    /**
     * 查询超时(毫秒)
     */
    private Integer timeoutMs;

    /**
     * 状态(0:禁用,1:启用)
     */
    private Integer status;

    /**
     * 创建时间
     */
    private LocalDateTime createTime;

    /**
     * 更新时间
     */
    private LocalDateTime updateTime;

}
