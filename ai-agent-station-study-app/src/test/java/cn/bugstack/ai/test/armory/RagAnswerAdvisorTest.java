package cn.bugstack.ai.test.armory;

import cn.bugstack.ai.domain.agent.service.armory.factory.element.RagAnswerAdvisor;
import org.junit.Test;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;

import java.util.HashMap;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * {@link RagAnswerAdvisor#before} 单元测试。
 * <p>
 * 为什么需要它：RAG 在本项目里默认关闭（DeepSeek 不提供 embeddings 接口），
 * 因此这两处缺陷无法通过端到端方式验证，只能用桩化的 VectorStore 做确定性验证。
 * <p>
 * 覆盖改造前的两个缺陷：
 * <ol>
 *     <li>用 {@code new PromptTemplate(userText).render()} 生成检索 query —— 用户文本里的
 *         {@code {}} 会被 StringTemplate 当占位符解析并抛异常。排障场景用户粘贴 JSON / 日志是常态。</li>
 *     <li>用 {@code Prompt.builder().messages(...)} 重建消息列表 —— 会把 ChatClient 上的
 *         SystemMessage（承载 MCP 工具使用约束）整段丢弃。</li>
 * </ol>
 * 注意：这是纯单元测试，不依赖数据库、Docker、向量库或任何外部服务。
 */
public class RagAnswerAdvisorTest {

    private static final String SYSTEM_MARKER = "SYS-MARKER-工具使用约束：只依据检索结果作答";
    private static final String RETRIEVED_TEXT = "检索到的上下文：账号 quick_publisher_88 触发了限流";

    /** 只实现 VectorStore 的抽象方法，避免依赖真实向量库 */
    static class CapturingVectorStore implements VectorStore {

        private final List<Document> documents;

        SearchRequest lastRequest;

        CapturingVectorStore(List<Document> documents) {
            this.documents = documents;
        }

        @Override
        public void add(List<Document> documents) {
            // 测试不需要
        }

        @Override
        public void delete(List<String> ids) {
            // 测试不需要
        }

        @Override
        public void delete(Filter.Expression filterExpression) {
            // 测试不需要
        }

        @Override
        public List<Document> similaritySearch(SearchRequest request) {
            this.lastRequest = request;
            return documents;
        }
    }

    private static ChatClientRequest newRequest(String userText) {
        Prompt prompt = new Prompt(List.of(
                new SystemMessage(SYSTEM_MARKER),
                new UserMessage(userText)));
        return new ChatClientRequest(prompt, new HashMap<>());
    }

    private static CapturingVectorStore stubStore() {
        return new CapturingVectorStore(List.of(new Document(RETRIEVED_TEXT)));
    }

    /**
     * 缺陷 1：用户文本含 JSON 花括号时不得抛异常，且检索 query 必须是用户原文。
     */
    @Test
    public void jsonBracesInUserTextShouldNotBreakAndQueryShouldBeRawUserText() {
        CapturingVectorStore vectorStore = stubStore();
        RagAnswerAdvisor advisor = new RagAnswerAdvisor(vectorStore,
                SearchRequest.builder().topK(4).build());

        String userText = "这条日志报错了 {\"orderNo\":\"12345\",\"code\":500} 帮我看看";

        // 改造前这里会抛 StringTemplate 相关异常
        ChatClientRequest result = advisor.before(newRequest(userText), null);

        assertNotNull(result);
        assertNotNull("应当执行过向量检索", vectorStore.lastRequest);
        assertEquals("检索 query 必须等于用户原文，不能过模板引擎", userText, vectorStore.lastRequest.getQuery());
        assertEquals(4, vectorStore.lastRequest.getTopK());
    }

    /**
     * 缺陷 2：SystemMessage 必须保留；同时检索到的上下文要真正注入进用户消息。
     */
    @Test
    public void systemMessageShouldBePreservedAndContextInjected() {
        RagAnswerAdvisor advisor = new RagAnswerAdvisor(stubStore(),
                SearchRequest.builder().topK(4).build());

        String userText = "最近有哪些账号触发了限流？";
        ChatClientRequest result = advisor.before(newRequest(userText), null);

        List<Message> instructions = result.prompt().getInstructions();

        boolean systemMessagePreserved = instructions.stream()
                .anyMatch(message -> message instanceof SystemMessage
                        && message.getText().contains(SYSTEM_MARKER));
        assertTrue("SystemMessage 必须被保留：改造前它会被整段丢弃，导致工具使用约束失效",
                systemMessagePreserved);

        boolean userMessagePresent = instructions.stream().anyMatch(message -> message instanceof UserMessage);
        assertTrue("用户消息应存在", userMessagePresent);

        String advisedUserText = result.prompt().getUserMessage().getText();
        assertTrue("用户原始文本应保留", advisedUserText.contains(userText));
        assertTrue("检索到的上下文必须真正替换进提示词（改造前占位符 {question_answer_context} 从未被替换）",
                advisedUserText.contains(RETRIEVED_TEXT));
    }

    /**
     * 顾问配置里的 filterExpression 应生效——这是后续多租户隔离按租户过滤的挂载点。
     */
    @Test
    public void filterExpressionFromAdvisorConfigShouldBeApplied() {
        CapturingVectorStore vectorStore = stubStore();
        RagAnswerAdvisor advisor = new RagAnswerAdvisor(vectorStore,
                SearchRequest.builder().topK(2).filterExpression("knowledge == 'test'").build());

        advisor.before(newRequest("排查限流问题"), null);

        assertNotNull(vectorStore.lastRequest);
        assertNotNull("SearchRequest 上的 filterExpression 应被传递给向量库",
                vectorStore.lastRequest.getFilterExpression());
    }

}
