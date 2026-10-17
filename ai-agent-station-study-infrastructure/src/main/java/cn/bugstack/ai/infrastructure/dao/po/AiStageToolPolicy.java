package cn.bugstack.ai.infrastructure.dao.po;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 阶段-工具能力策略表
 *
 * @author miaowazhongzi
 * @description 阶段-工具能力策略表 PO 对象
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class AiStageToolPolicy {

    /**
     * 主键ID
     */
    private Long id;

    /**
     * 阶段（等于 ai_agent_flow_config.client_type，如 TASK_ANALYZER_CLIENT）
     */
    private String stageCode;

    /**
     * 能力(entity_meta/log_search/entity_query)
     */
    private String capability;

    /**
     * 是否允许(0:否,1:是)
     */
    private Integer allowed;

    /**
     * 创建时间
     */
    private LocalDateTime createTime;

    /**
     * 更新时间
     */
    private LocalDateTime updateTime;

}
