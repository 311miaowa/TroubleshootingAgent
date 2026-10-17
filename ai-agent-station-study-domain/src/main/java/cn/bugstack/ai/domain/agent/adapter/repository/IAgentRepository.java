package cn.bugstack.ai.domain.agent.adapter.repository;

import cn.bugstack.ai.domain.agent.model.valobj.*;

import java.util.List;
import java.util.Map;

/**
 * AiAgent 仓储接口
 *
 * @author miaowazhongzi
 * 2025/6/27 16:48
 */
public interface IAgentRepository {

    List<AiClientApiVO> queryAiClientApiVOListByClientIds(List<String> clientIdList);

    List<AiClientModelVO> AiClientModelVOByClientIds(List<String> clientIdList);

    List<AiClientToolMcpVO> AiClientToolMcpVOByClientIds(List<String> clientIdList);

    List<AiClientSystemPromptVO> AiClientSystemPromptVOByClientIds(List<String> clientIdList);

    Map<String, AiClientSystemPromptVO> queryAiClientSystemPromptMapByClientIds(List<String> clientIdList);

    List<AiClientAdvisorVO> AiClientAdvisorVOByClientIds(List<String> clientIdList);

    List<AiClientVO> AiClientVOByClientIds(List<String> clientIdList);

    List<AiClientApiVO> queryAiClientApiVOListByModelIds(List<String> modelIdList);

    List<AiClientModelVO> AiClientModelVOByModelIds(List<String> modelIdList);

    Map<String, AiAgentClientFlowConfigVO> queryAiAgentClientFlowConfig(String aiAgentId);

    /**
     * 查询客户端所属阶段编码（等于 ai_agent_flow_config.client_type）
     * <p>
     * 用于 M1.2 阶段裁剪：装配 ChatClient 时按阶段决定注入哪些语义工具。
     * 一个 clientId 通常只对应一个 client_type（角色稳定）；若跨多个 agent 出现多种角色，
     * 返回查询结果的第一条（实现侧按稳定顺序排序）。
     *
     * @param clientId 客户端ID
     * @return 阶段编码（如 TASK_ANALYZER_CLIENT）；无配置时返回 null
     */
    String queryClientTypeByClientId(String clientId);

}
