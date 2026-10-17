package cn.bugstack.ai.domain.agent.service.armory;

import cn.bugstack.ai.domain.agent.model.entity.ArmoryCommandEntity;
import cn.bugstack.ai.domain.agent.model.valobj.enums.AiAgentEnumVO;
import cn.bugstack.ai.domain.agent.model.valobj.enums.AiClientAdvisorTypeEnumVO;
import cn.bugstack.ai.domain.agent.model.valobj.AiClientAdvisorVO;
import cn.bugstack.ai.domain.agent.service.armory.factory.DefaultArmoryStrategyFactory;
import cn.bugstack.wrench.design.framework.tree.StrategyHandler;
import com.alibaba.fastjson.JSON;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 顾问角色节点
 *
 * @author miaowazhongzi
 * 2025/7/19 08:51
 */
@Slf4j
@Service
public class AiClientAdvisorNode extends AbstractArmorySupport {

    /**
     * 向量库：仅在 {@code xfg.embedding.enabled=true} 时存在，故为可选依赖。
     * <p>
     * 另有 starter 自动配置在未启用本项目向量库时会提供一个 VectorStore Bean，
     * 因此不能只靠"是否为 null"判断 RAG 是否可用，还需结合开关。
     */
    @Autowired(required = false)
    private VectorStore vectorStore;

    /** RAG 向量化开关，与对话模型（如 DeepSeek）的 key 解耦 */
    @Value("${xfg.embedding.enabled:false}")
    private boolean embeddingEnabled;

    /**
     * 被跳过的顾问 Bean 名集合在 DynamicContext 中的键。
     * AiClientNode 会按客户端引用的顾问名单逐个 getBean，若不告知跳过项，
     * 会因 Bean 未注册而抛 NoSuchBeanDefinitionException，导致整轮装配失败。
     */
    public static final String SKIPPED_ADVISOR_BEANS_KEY = "skippedAdvisorBeans";

    @Resource
    private AiClientNode aiClientNode;

    @Override
    protected String doApply(ArmoryCommandEntity requestParameter, DefaultArmoryStrategyFactory.DynamicContext dynamicContext) throws Exception {
        log.info("Ai Agent 构建节点，Advisor 顾问角色{}", JSON.toJSONString(requestParameter));

        List<AiClientAdvisorVO> aiClientAdvisorList = dynamicContext.getValue(dataName());

        if (aiClientAdvisorList == null || aiClientAdvisorList.isEmpty()) {
            log.warn("没有需要被初始化的 ai client advisor");
            return router(requestParameter, dynamicContext);
        }

        Set<String> skippedAdvisorBeans = new HashSet<>();

        for (AiClientAdvisorVO aiClientAdvisorVO : aiClientAdvisorList) {
            // 知识库（RAG）顾问需要向量库。若未启用向量化，明确报错并跳过该顾问，
            // 避免整个装配失败、也避免运行时拿对话模型的 key 去打 embeddings 接口得到费解的报错。
            if (AiClientAdvisorTypeEnumVO.RAG_ANSWER.getCode().equals(aiClientAdvisorVO.getAdvisorType())
                    && (!embeddingEnabled || vectorStore == null)) {
                log.error("检测到 RagAnswer（知识库）顾问配置 advisorId={}，但 RAG 向量化未启用，已跳过该顾问。" +
                                "原因：DeepSeek 等对话模型不提供 embeddings 接口，向量化需单独配置。" +
                                "如需启用：设置 xfg.embedding.enabled=true，并配置 xfg.embedding.base-url / xfg.embedding.api-key " +
                                "指向任意 OpenAI 兼容的向量化服务（参考 docs/dev-ops/pgvector/sql/init.sql 中的表结构）。",
                        aiClientAdvisorVO.getAdvisorId());
                skippedAdvisorBeans.add(beanName(aiClientAdvisorVO.getAdvisorId()));
                continue;
            }

            // 构建顾问访问对象
            Advisor advisor = createAdvisor(aiClientAdvisorVO);
            // 注册Bean对象
            registerBean(beanName(aiClientAdvisorVO.getAdvisorId()), Advisor.class, advisor);
        }

        // 传递给 AiClientNode，使其不再引用未被注册的顾问
        dynamicContext.setValue(SKIPPED_ADVISOR_BEANS_KEY, skippedAdvisorBeans);

        return router(requestParameter, dynamicContext);
    }

    @Override
    public StrategyHandler<ArmoryCommandEntity, DefaultArmoryStrategyFactory.DynamicContext, String> get(ArmoryCommandEntity requestParameter, DefaultArmoryStrategyFactory.DynamicContext dynamicContext) throws Exception {
        return aiClientNode;
    }

    protected String beanName(String beanId) {
        return AiAgentEnumVO.AI_CLIENT_ADVISOR.getBeanName(beanId);
    }

    @Override
    protected String dataName() {
        return AiAgentEnumVO.AI_CLIENT_ADVISOR.getDataName();
    }

    private Advisor createAdvisor(AiClientAdvisorVO aiClientAdvisorVO) {
        String advisorType = aiClientAdvisorVO.getAdvisorType();
        AiClientAdvisorTypeEnumVO advisorTypeEnum = AiClientAdvisorTypeEnumVO.getByCode(advisorType);
        return advisorTypeEnum.createAdvisor(aiClientAdvisorVO, vectorStore);
    }

}
