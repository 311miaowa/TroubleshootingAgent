package cn.bugstack.ai.config;

import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * RAG 向量库配置。
 * <p>
 * 关于开关：对话模型（如 DeepSeek）通常<b>不提供 embeddings 接口</b>，向量化必须单独配置。
 * 因此本 Bean 由 {@code xfg.embedding.enabled} 显式控制，与对话模型的 key 完全解耦：
 * <ul>
 *     <li>{@code xfg.embedding.enabled=false}（默认）：不创建向量库，RAG 顾问会被跳过并给出明确提示；</li>
 *     <li>{@code xfg.embedding.enabled=true}：使用 {@code xfg.embedding.base-url/api-key} 指向
 *         任意 OpenAI 兼容的向量化服务。</li>
 * </ul>
 * <p>
 * 注意：原实现使用 {@code @ConditionalOnBean(name = "pgVectorJdbcTemplate")}。该注解在用户
 * {@code @Configuration} 上存在解析顺序问题（Spring 官方明确不推荐此种用法），
 * 实际效果是本 Bean 被静默跳过、改用 starter 自动配置的向量库。这里改为属性驱动，行为确定。
 */
@Configuration
public class AiAgentConfig {

    /**
     * -- 删除旧的表（如果存在）
     * DROP TABLE IF EXISTS public.vector_store_openai;
     * <p>
     * -- 创建新的表，使用UUID作为主键
     * CREATE TABLE public.vector_store_openai (
     * id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
     * content TEXT NOT NULL,
     * metadata JSONB,
     * embedding VECTOR(1536)
     * );
     * <p>
     * SELECT * FROM vector_store_openai
     */
    @Bean("vectorStore")
    @ConditionalOnProperty(prefix = "xfg.embedding", name = "enabled", havingValue = "true")
    public PgVectorStore pgVectorStore(@Value("${xfg.embedding.base-url}") String baseUrl,
                                       @Value("${xfg.embedding.api-key}") String apiKey,
                                       @Value("${xfg.embedding.vector-table:vector_store_openai}") String vectorTableName,
                                       @Value("${xfg.embedding.dimensions:0}") int dimensions,
                                       @Qualifier("pgVectorJdbcTemplate") JdbcTemplate jdbcTemplate) {

        OpenAiApi openAiApi = OpenAiApi.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .build();

        OpenAiEmbeddingModel embeddingModel = new OpenAiEmbeddingModel(openAiApi);

        // 注意类型必须是 PgVectorStore.PgVectorStoreBuilder：
        // 若写成 PgVectorStore.Builder，会被解析为继承自 VectorStore 的嵌套类型 VectorStore.Builder，
        // 从而丢失 dimensions(int) 等方法。
        PgVectorStore.PgVectorStoreBuilder builder = PgVectorStore.builder(jdbcTemplate, embeddingModel)
                .vectorTableName(vectorTableName);

        // 仅在显式配置维度时才设置，避免与既有表结构（vector_store_openai 为 1536 维）产生不一致
        if (dimensions > 0) {
            builder.dimensions(dimensions);
        }

        return builder.build();
    }

    @Bean
    public TokenTextSplitter tokenTextSplitter() {
        return new TokenTextSplitter();
    }

}
