package cn.bugstack.ai.domain.agent.service.execute.auto;

import cn.bugstack.ai.domain.agent.model.entity.AutoAgentExecuteResultEntity;
import cn.bugstack.ai.domain.agent.model.entity.ExecuteCommandEntity;
import cn.bugstack.ai.domain.agent.model.valobj.datasource.FaultWindow;
import cn.bugstack.ai.domain.agent.service.execute.IExecuteStrategy;
import cn.bugstack.ai.domain.agent.service.execute.auto.step.factory.DefaultAutoAgentExecuteStrategyFactory;
import cn.bugstack.wrench.design.framework.tree.StrategyHandler;
import com.alibaba.fastjson.JSON;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;

/**
 * 自动执行策略
 * @author miaowazhongzi
 * 2025/8/5 09:49
 */
@Slf4j
@Service
public class AutoAgentExecuteStrategy implements IExecuteStrategy {

    @Resource
    private DefaultAutoAgentExecuteStrategyFactory defaultAutoAgentExecuteStrategyFactory;

    @Override
    public void execute(ExecuteCommandEntity executeCommandEntity, ResponseBodyEmitter emitter) throws Exception {
        StrategyHandler<ExecuteCommandEntity, DefaultAutoAgentExecuteStrategyFactory.DynamicContext, String> executeHandler
                = defaultAutoAgentExecuteStrategyFactory.armoryStrategyHandler();
        
        // 创建动态上下文并初始化必要字段
        DefaultAutoAgentExecuteStrategyFactory.DynamicContext dynamicContext = new DefaultAutoAgentExecuteStrategyFactory.DynamicContext();
        dynamicContext.setMaxStep(executeCommandEntity.getMaxStep() != null ? executeCommandEntity.getMaxStep() : 3);
        dynamicContext.setExecutionHistory(new StringBuilder());
        dynamicContext.setCurrentTask(executeCommandEntity.getMessage());
        dynamicContext.setValue("emitter", emitter);

        // M8：解析故障窗（可选）。没有故障窗时行为与旧版完全一致
        FaultWindow faultWindow = FaultWindow.parse(executeCommandEntity.getFaultStart(),
                executeCommandEntity.getFaultEnd(),
                executeCommandEntity.getPrevFaultEnd(),
                executeCommandEntity.getNextFaultStart(),
                executeCommandEntity.getFaultLabel());
        if (faultWindow != null) {
            java.time.Instant now = java.time.Instant.now();
            dynamicContext.setValue(FaultWindow.CONTEXT_KEY, faultWindow);
            log.info("本次会话故障窗：{}", faultWindow.describe(now));
            // 提示词侧上下文块（M8 决策 2 的"消息模板"补充）：只写机械事实，不含任何判断
            String decorated = decorateWithFaultWindow(executeCommandEntity.getMessage(), faultWindow, now);
            executeCommandEntity.setMessage(decorated);
            dynamicContext.setCurrentTask(decorated);
        }

        String apply = executeHandler.apply(executeCommandEntity, dynamicContext);
        log.info("测试结果:{}", apply);
        
        // 发送完成标识
        try {
            AutoAgentExecuteResultEntity completeResult = AutoAgentExecuteResultEntity.createCompleteResult(executeCommandEntity.getSessionId());
            // 发送SSE格式的数据
            String sseData = "data: " + JSON.toJSONString(completeResult) + "\n\n";
            emitter.send(sseData);
        } catch (Exception e) {
            log.error("发送完成标识失败：{}", e.getMessage(), e);
        }
    }

    /**
     * 把故障窗上下文块追加到用户消息（M8 §10 的"提示词侧补充"）。
     * <p>
     * <b>只写机械事实</b>：故障窗/前基线/后基线的**时间范围**与"进行中"这类状态，外加使用纪律
     * （fault 与 baseline 要分开陈述、缺基线时以 warnings 为准）。<b>不写任何判断</b>——"所以是限流导致"
     * 这类结论必须由模型自己根据数据给出。
     * <p>
     * 幂等：消息里已含标记则不重复追加（重试/多步复用同一实体时不会叠加）。
     */
    private String decorateWithFaultWindow(String message, FaultWindow window, java.time.Instant now) {
        String text = message == null ? "" : message;
        if (text.contains(FAULT_WINDOW_MARK)) {
            return text;
        }
        return text + "\n\n" + FAULT_WINDOW_MARK + window.describe(now)
                + "。请把 fault 段与 baseline 段**分开陈述**；缺少基线时以工具返回的 warnings 为准，不要自行假设基线。";
    }

    /**
     * 上下文块标记（用于幂等判断）
     */
    static final String FAULT_WINDOW_MARK = "【故障窗上下文（框架注入，非用户输入）】";

}
