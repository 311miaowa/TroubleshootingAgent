package cn.bugstack.ai.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;

/**
 * AutoAgent 请求 DTO
 *
 * @author miaowazhongzi
 * 2025/1/15 10:00
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class AutoAgentRequestDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * AI智能体ID
     */
    private String aiAgentId;

    /**
     * 用户消息
     */
    private String message;

    /**
     * 会话ID
     */
    private String sessionId;

    /**
     * 最大执行步数
     */
    private Integer maxStep;

    /**
     * 故障开始（ISO-8601，可选）。全部故障窗字段都为空时，行为与旧版完全一致（M8 §5.1）
     */
    private String faultStart;

    /**
     * 故障结束（ISO-8601，可选）。为空表示故障进行中，按当前时间推导
     */
    private String faultEnd;

    /**
     * 上次故障结束时间（ISO-8601，可选）：用于构造"避开余波"的前基线
     */
    private String prevFaultEnd;

    /**
     * 下次故障开始时间（ISO-8601，可选）：仅当给出时才构造后基线
     */
    private String nextFaultStart;

    /**
     * 故障标签（可选，仅用于展示与提示词上下文块）
     */
    private String faultLabel;

}