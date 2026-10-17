package cn.bugstack.ai.domain.agent.service.execute.auto.step;

import cn.bugstack.ai.domain.agent.adapter.repository.IAgentRepository;
import cn.bugstack.ai.domain.agent.model.entity.AutoAgentExecuteResultEntity;
import cn.bugstack.ai.domain.agent.model.entity.ExecuteCommandEntity;
import cn.bugstack.ai.domain.agent.model.entity.ToolCallEventEntity;
import cn.bugstack.ai.domain.agent.model.valobj.datasource.FaultWindow;
import cn.bugstack.ai.domain.agent.model.valobj.enums.AiAgentEnumVO;
import cn.bugstack.ai.domain.agent.service.armory.AbstractArmorySupport;
import cn.bugstack.ai.domain.agent.service.armory.registry.BeanRegistry;
import cn.bugstack.ai.domain.agent.service.datasource.tool.ToolCallEventSink;
import cn.bugstack.ai.domain.agent.service.execute.auto.step.factory.DefaultAutoAgentExecuteStrategyFactory;
import cn.bugstack.wrench.design.framework.tree.AbstractMultiThreadStrategyRouter;
import com.alibaba.fastjson.JSON;
import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.context.ApplicationContext;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

/**
 * @author miaowazhongzi
 * 2025/7/27 16:48
 */
public abstract class AbstractExecuteSupport extends AbstractMultiThreadStrategyRouter<ExecuteCommandEntity, DefaultAutoAgentExecuteStrategyFactory.DynamicContext, String> {

    private final Logger log = LoggerFactory.getLogger(AbstractExecuteSupport.class);

    @Resource
    protected ApplicationContext applicationContext;

    @Resource
    protected IAgentRepository repository;

    @Resource
    protected BeanRegistry beanRegistry;

    public static final String CHAT_MEMORY_CONVERSATION_ID_KEY = "chat_memory_conversation_id";
    public static final String CHAT_MEMORY_RETRIEVE_SIZE_KEY = "chat_memory_response_size";

    @Override
    protected void multiThread(ExecuteCommandEntity requestParameter, DefaultAutoAgentExecuteStrategyFactory.DynamicContext dynamicContext) throws ExecutionException, InterruptedException, TimeoutException {

    }

    protected ChatClient getChatClientByClientId(String clientId) {
        return getBean(AiAgentEnumVO.AI_CLIENT.getBeanName(clientId));
    }

    /**
     * 获取 Bean。
     * <p>
     * 走 {@link BeanRegistry} 而非直接 applicationContext：与装配写侧共用同一把锁，
     * 避免在配置热更新"移除旧 Bean → 注册新 Bean"的瞬间抛出 NoSuchBeanDefinitionException。
     */
    protected <T> T getBean(String beanName) {
        return beanRegistry.getBean(beanName);
    }

    /**
     * 通用的SSE结果发送方法
     * @param dynamicContext 动态上下文
     * @param result 要发送的结果实体
     */
    protected void sendSseResult(DefaultAutoAgentExecuteStrategyFactory.DynamicContext dynamicContext, 
                                AutoAgentExecuteResultEntity result) {
        try {
            ResponseBodyEmitter emitter = dynamicContext.getValue("emitter");
            if (emitter != null) {
                // 发送SSE格式的数据
                String sseData = "data: " + JSON.toJSONString(result) + "\n\n";
                emitter.send(sseData);
            }
        } catch (IOException e) {
            log.error("发送SSE结果失败：{}", e.getMessage(), e);
        }
    }

    /**
     * 构造 Tool Call 事件上下文（M3.1）：经 Spring AI 的 {@code toolContext} 通道，
     * 把携带本次请求 sessionId / step / emitter 的事件发送器注入工具回调。
     */
    protected Map<String, Object> toolCallContext(DefaultAutoAgentExecuteStrategyFactory.DynamicContext dynamicContext,
                                                  String sessionId) {
        ToolCallEventSink sink = new ToolCallEventSink(sessionId, dynamicContext.getStep(),
                event -> sendToolCallEvent(dynamicContext, event));
        Map<String, Object> context = new HashMap<>();
        context.put(ToolCallEventSink.TOOL_CONTEXT_KEY, sink);
        // M8：故障窗随 ToolContext 进入工具执行（不进 schema，与 tenantId 同一条纪律）
        FaultWindow faultWindow = dynamicContext.getValue(FaultWindow.CONTEXT_KEY);
        if (faultWindow != null) {
            context.put(FaultWindow.CONTEXT_KEY, faultWindow);
        }
        return context;
    }

    /**
     * 发送 Tool Call 结构化事件（复用与 step 级结果一致的 SSE {@code data:} 帧格式）
     */
    private void sendToolCallEvent(DefaultAutoAgentExecuteStrategyFactory.DynamicContext dynamicContext,
                                   ToolCallEventEntity event) {
        try {
            ResponseBodyEmitter emitter = dynamicContext.getValue("emitter");
            if (emitter != null) {
                emitter.send("data: " + JSON.toJSONString(event) + "\n\n");
            }
        } catch (IOException e) {
            log.error("发送工具调用事件失败：{}", e.getMessage(), e);
        }
    }

}
