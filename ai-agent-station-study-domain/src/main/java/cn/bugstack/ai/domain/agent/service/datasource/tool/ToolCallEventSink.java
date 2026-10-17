package cn.bugstack.ai.domain.agent.service.datasource.tool;

import cn.bugstack.ai.domain.agent.model.entity.ToolCallEventEntity;

import java.util.function.Consumer;

/**
 * Tool Call 事件发送器（M3.1）
 * <p>
 * 由 {@code AbstractExecuteSupport} 在每次 step 调用前构造（携带本次请求的 sessionId / step /
 * emitter），经 Spring AI 的 {@code toolContext} 通道注入到 {@link DataSourceToolCallbackFactory}
 * 生成的 {@code ToolCallback} 里，让工具回调在<b>执行当场</b>把 start / result 事件实时发出。
 * <p>
 * 抽象成 Consumer 而非直接持有 {@code ResponseBodyEmitter}，是为了让工具工厂可脱离 servlet 单测。
 *
 * @author miaowazhongzi
 */
public final class ToolCallEventSink {

    /**
     * toolContext map 里本发送器的键
     */
    public static final String TOOL_CONTEXT_KEY = "_m3_tool_call_event_sink";

    private final String sessionId;

    private final int step;

    private final Consumer<ToolCallEventEntity> emitter;

    public ToolCallEventSink(String sessionId, int step, Consumer<ToolCallEventEntity> emitter) {
        this.sessionId = sessionId;
        this.step = step;
        this.emitter = emitter;
    }

    public void start(String toolName, String dsId, String entityCode, String arguments) {
        emit(ToolCallEventEntity.start(toolName, dsId, entityCode, sessionId, step, arguments));
    }

    public void result(String toolName, String dsId, String entityCode, String arguments, String result) {
        emit(ToolCallEventEntity.result(toolName, dsId, entityCode, sessionId, step, arguments, result));
    }

    private void emit(ToolCallEventEntity event) {
        if (emitter != null) {
            emitter.accept(event);
        }
    }

}
