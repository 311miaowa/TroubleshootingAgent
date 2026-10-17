package cn.bugstack.ai.domain.agent.service.armory;

import cn.bugstack.ai.domain.agent.model.entity.ArmoryCommandEntity;
import cn.bugstack.ai.domain.agent.model.valobj.enums.AiAgentEnumVO;
import cn.bugstack.ai.domain.agent.model.valobj.AiClientSystemPromptVO;
import cn.bugstack.ai.domain.agent.model.valobj.AiClientVO;
import cn.bugstack.ai.domain.agent.service.armory.factory.DefaultArmoryStrategyFactory;
import cn.bugstack.ai.domain.agent.service.datasource.tool.DataSourceToolCallbackFactory;
import cn.bugstack.wrench.design.framework.tree.StrategyHandler;
import com.alibaba.fastjson.JSON;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * ai agent 客户端对话对象节点
 *
 * @author miaowazhongzi
 * 2025/7/19 09:17
 */
@Slf4j
@Service
public class AiClientNode extends AbstractArmorySupport {

    @Resource
    private DataSourceToolCallbackFactory dataSourceToolCallbackFactory;

    @Override
    protected String doApply(ArmoryCommandEntity requestParameter, DefaultArmoryStrategyFactory.DynamicContext dynamicContext) throws Exception {
        log.info("Ai Agent 构建节点，客户端{}", JSON.toJSONString(requestParameter));

        List<AiClientVO> aiClientList = dynamicContext.getValue(dataName());

        if (null == aiClientList || aiClientList.isEmpty()) {
            return router(requestParameter, dynamicContext);
        }

        Map<String, AiClientSystemPromptVO> systemPromptMap = dynamicContext.getValue(AiAgentEnumVO.AI_CLIENT_SYSTEM_PROMPT.getDataName());

        for (AiClientVO aiClientVO : aiClientList) {
            // 1. 预设话术
            StringBuilder defaultSystem = new StringBuilder("Ai 智能体 \r\n");
            List<String> promptIdList = aiClientVO.getPromptIdList();
            for (String promptId : promptIdList) {
                AiClientSystemPromptVO aiClientSystemPromptVO = systemPromptMap.get(promptId);
                defaultSystem.append(aiClientSystemPromptVO.getPromptContent());
            }

            // 2. 对话模型
            OpenAiChatModel chatModel = getBean(aiClientVO.getModelBeanName());

            // 3. 语义工具（M1.2：按阶段裁剪，分析/监督阶段不给执行类能力）
            String clientType = resolveClientType(aiClientVO.getClientId());
            List<ToolCallback> toolCallbacks =
                    dataSourceToolCallbackFactory.buildFor(aiClientVO.getClientId(), clientType);

            // 4. advisor 顾问角色
            //    跳过在 AiClientAdvisorNode 中因能力缺失（如未启用 RAG 向量化）而被跳过的顾问，
            //    并对其它缺失的顾问做容错，避免单个组件问题导致整轮装配失败。
            List<Advisor> advisors = new ArrayList<>();
            Set<String> skippedAdvisorBeans = dynamicContext.getValue(AiClientAdvisorNode.SKIPPED_ADVISOR_BEANS_KEY);
            List<String> advisorBeanNameList = aiClientVO.getAdvisorBeanNameList();
            for (String advisorBeanName : advisorBeanNameList) {
                if (skippedAdvisorBeans != null && skippedAdvisorBeans.contains(advisorBeanName)) {
                    log.warn("顾问因能力未启用被跳过：clientId={}，advisorBean={}", aiClientVO.getClientId(), advisorBeanName);
                    continue;
                }
                if (!containsBean(advisorBeanName)) {
                    log.warn("顾问 Bean 不存在，已跳过：clientId={}，advisorBean={}", aiClientVO.getClientId(), advisorBeanName);
                    continue;
                }
                advisors.add(getBean(advisorBeanName));
            }

            Advisor[] advisorArray = advisors.toArray(new Advisor[]{});

            // 5. 构建对话客户端
            ChatClient chatClient = ChatClient.builder(chatModel)
                    .defaultSystem(defaultSystem.toString())
                    .defaultToolCallbacks(toolCallbacks)
                    .defaultAdvisors(advisorArray)
                    .build();

            registerBean(beanName(aiClientVO.getClientId()), ChatClient.class, chatClient);
        }

        return router(requestParameter, dynamicContext);
    }

    @Override
    public StrategyHandler<ArmoryCommandEntity, DefaultArmoryStrategyFactory.DynamicContext, String> get(ArmoryCommandEntity requestParameter, DefaultArmoryStrategyFactory.DynamicContext dynamicContext) throws Exception {
        return defaultStrategyHandler;
    }

    /**
     * 解析客户端所属阶段编码（M1.2 阶段裁剪用）
     * <p>
     * 解析失败或未配置时返回 null，语义工具工厂会按"不裁剪"处理（返回全部语义工具），
     * 保证单个客户端的阶段配置缺失不会让整轮装配失败。
     */
    private String resolveClientType(String clientId) {
        try {
            return repository.queryClientTypeByClientId(clientId);
        } catch (Exception e) {
            log.warn("解析客户端阶段失败 clientId={}，回退为不裁剪：{}", clientId, e.getMessage());
            return null;
        }
    }

    @Override
    protected String beanName(String id) {
        return AiAgentEnumVO.AI_CLIENT.getBeanName(id);
    }

    @Override
    protected String dataName() {
        return AiAgentEnumVO.AI_CLIENT.getDataName();
    }

}
