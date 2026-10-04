package com.urban.ai.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Spring AI wiring. Replaces the earlier LangChain4j configuration.
 *
 * <ul>
 *   <li>{@code ChatModel} / {@code EmbeddingModel} come from the OpenAI starter (see {@code spring.ai.openai.*}
 *       in application.yml; point {@code OPENAI_BASE_URL} at Ollama/vLLM for a local model).</li>
 *   <li>The persistent {@code VectorStore} comes from the pgvector starter ({@code spring.ai.vectorstore.pgvector.*}).
 *       When {@code ai.pgvector.enabled=false} an in-memory {@link SimpleVectorStore} is used instead.</li>
 *   <li>{@link ChatMemory} is a sliding window over a Redis-backed repository (see {@code RedisChatMemoryRepository}).</li>
 * </ul>
 */
@Configuration
@Slf4j
public class AiConfig {

    @Bean
    public ChatClient chatClient(ChatClient.Builder builder) {
        return builder.build();
    }

    /**
     * Only registered when pgvector is switched off. Because this is a user-defined {@link VectorStore} bean,
     * the pgvector auto-configuration backs off (it is {@code @ConditionalOnMissingBean(VectorStore.class)}).
     */
    @Bean
    @ConditionalOnProperty(name = "ai.pgvector.enabled", havingValue = "false")
    public VectorStore inMemoryVectorStore(EmbeddingModel embeddingModel) {
        log.info("pgvector disabled via config; using in-memory vector store (embeddings will NOT persist)");
        return SimpleVectorStore.builder(embeddingModel).build();
    }

    @Bean
    public ChatMemory chatMemory(ChatMemoryRepository repository,
                                 @Value("${ai.chat-memory.max-messages:20}") int maxMessages) {
        return MessageWindowChatMemory.builder()
                .chatMemoryRepository(repository)
                .maxMessages(maxMessages)
                .build();
    }

    @Bean
    public WebClient.Builder webClientBuilder() {
        return WebClient.builder();
    }
}
