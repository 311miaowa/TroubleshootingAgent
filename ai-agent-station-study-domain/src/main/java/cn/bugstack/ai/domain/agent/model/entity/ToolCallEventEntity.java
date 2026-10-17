package cn.bugstack.ai.domain.agent.model.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Tool Call 结构化事件（M3.1）
 * <p>
 * 模型每调用一次语义工具，经 SSE 流出两条事件：{@code start}（调了哪个工具 + 入参）与
 * {@code result}（返回了什么，含 {@code rawRef} / 行级 {@code _rawRef}）。
 * 证据面板据此渲染工具调用过程并逐条回指原文。
 *
 * @author miaowazhongzi
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class ToolCallEventEntity {

    public static final String TYPE = "tool_call";

    public static final String SUBTYPE_START = "start";

    public static final String SUBTYPE_RESULT = "result";

    /**
     * 事件类型，固定 "tool_call"
     */
    private String type;

    /**
     * 子类型：start / result
     */
    private String subType;

    /**
     * 工具名：query_logs / query_orders / describe_entity
     */
    private String toolName;

    /**
     * 数据源ID（describe_entity 为 null）
     */
    private String dsId;

    /**
     * 实体编码（logs / orders；describe_entity 为 null）
     */
    private String entityCode;

    /**
     * 当前执行步骤
     */
    private Integer step;

    /**
     * 会话ID
     */
    private String sessionId;

    /**
     * 模型传入的 toolInput 原样（JSON 字符串）
     */
    private String arguments;

    /**
     * 工具返回结果（仅 subType=result 时非空；为 QueryResult 的 JSON）
     */
    private String result;

    /**
     * 时间戳
     */
    private Long timestamp;

    public static ToolCallEventEntity start(String toolName, String dsId, String entityCode,
                                            String sessionId, int step, String arguments) {
        return ToolCallEventEntity.builder()
                .type(TYPE).subType(SUBTYPE_START)
                .toolName(toolName).dsId(dsId).entityCode(entityCode)
                .sessionId(sessionId).step(step)
                .arguments(arguments)
                .timestamp(System.currentTimeMillis())
                .build();
    }

    public static ToolCallEventEntity result(String toolName, String dsId, String entityCode,
                                             String sessionId, int step, String arguments, String result) {
        return ToolCallEventEntity.builder()
                .type(TYPE).subType(SUBTYPE_RESULT)
                .toolName(toolName).dsId(dsId).entityCode(entityCode)
                .sessionId(sessionId).step(step)
                .arguments(arguments).result(result)
                .timestamp(System.currentTimeMillis())
                .build();
    }

}
