package cn.bugstack.ai.domain.agent.model.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 执行命令实体
 *
 * @author miaowazhongzi
 * 2025/7/27 16:46
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class ExecuteCommandEntity {

    private String aiAgentId;

    private String message;

    private String sessionId;

    private Integer maxStep;

    /**
     * 故障开始（ISO-8601，可选）。为空 = 本次没有故障窗，行为与旧版一致（M8 §5.1）
     */
    private String faultStart;

    /**
     * 故障结束（ISO-8601，可选）。为空表示"故障进行中"，按当前时间推导
     */
    private String faultEnd;

    /**
     * 上次故障结束时间（ISO-8601，可选）。用于构造"避开余波"的前基线
     */
    private String prevFaultEnd;

    /**
     * 下次故障开始时间（ISO-8601，可选）。仅当给出时才构造后基线（不猜）
     */
    private String nextFaultStart;

    /**
     * 故障标签（可选，仅供人看与提示词上下文块，不参与查询）
     */
    private String faultLabel;

}
