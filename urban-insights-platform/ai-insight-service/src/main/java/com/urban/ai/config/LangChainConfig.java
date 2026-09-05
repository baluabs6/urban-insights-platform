package com.urban.ai.config;

import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import dev.langchain4j.store.embedding.pgvector.PgVectorEmbeddingStore;
import dev.langchain4j.data.segment.TextSegment;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;

@Configuration
@Slf4j
public class LangChainConfig {

    /**
     * Chat model used for the final "generate" step of RAG.
     * baseUrl is overridable so this can point at OpenAI, Azure OpenAI, or a
     * self-hosted OpenAI-compatible endpoint (vLLM / Ollama / LocalAI) running
     * an open model — same LangChain4j code either way.
     */
    @Bean
    public ChatLanguageModel chatLanguageModel(
            @Value("${ai.openai.api-key:demo-key}") String apiKey,
            @Value("${ai.openai.base-url:https://api.openai.com/v1}") String baseUrl,
            @Value("${ai.openai.model:gpt-4o-mini}") String modelName) {
        return OpenAiChatModel.builder()
                .apiKey(apiKey)
                .baseUrl(baseUrl)
                .modelName(modelName)
                .temperature(0.2)
                .timeout(Duration.ofSeconds(30))
                .logRequests(false)
                .logResponses(false)
                .build();
    }

    /** Embedding model used to vectorize retrieved traffic/complaint text for similarity search. */
    @Bean
    public EmbeddingModel embeddingModel(
            @Value("${ai.openai.api-key:demo-key}") String apiKey,
            @Value("${ai.openai.base-url:https://api.openai.com/v1}") String baseUrl) {
        return OpenAiEmbeddingModel.builder()
                .apiKey(apiKey)
                .baseUrl(baseUrl)
                .modelName("text-embedding-3-small")
                .build();
    }

    /**
     * Persistent vector store backed by pgvector, reusing the same PostgreSQL
     * instance traffic-service already runs on (different logical table:
     * "urban_embeddings"). Falls back to an in-memory store — logging a warning —
     * if pgvector isn't reachable, so local dev without Postgres still works.
     *
     * Requires the pgvector extension: CREATE EXTENSION IF NOT EXISTS vector;
     * (run once against the target database — see README).
     */
    @Bean
    public EmbeddingStore<TextSegment> embeddingStore(
            @Value("${ai.pgvector.enabled:true}") boolean enabled,
            @Value("${ai.pgvector.host:localhost}") String host,
            @Value("${ai.pgvector.port:5432}") int port,
            @Value("${ai.pgvector.database:urban_traffic}") String database,
            @Value("${ai.pgvector.user:urban_user}") String user,
            @Value("${ai.pgvector.password:urban_pass}") String password,
            @Value("${ai.pgvector.table:urban_embeddings}") String table,
            @Value("${ai.pgvector.dimension:1536}") int dimension) {

        if (!enabled) {
            log.info("pgvector disabled via config; using in-memory embedding store");
            return new InMemoryEmbeddingStore<>();
        }
        try {
            EmbeddingStore<TextSegment> store = PgVectorEmbeddingStore.builder()
                    .host(host)
                    .port(port)
                    .database(database)
                    .user(user)
                    .password(password)
                    .table(table)
                    .dimension(dimension)
                    .createTable(true)
                    .dropTableFirst(false)
                    .build();
            log.info("Connected pgvector embedding store at {}:{}/{} (table={})", host, port, database, table);
            return store;
        } catch (Exception e) {
            log.warn("Could not initialize pgvector store ({}); falling back to in-memory store. " +
                    "Embeddings will NOT persist across restarts.", e.getMessage());
            return new InMemoryEmbeddingStore<>();
        }
    }

    @Bean
    public WebClient.Builder webClientBuilder() {
        return WebClient.builder();
    }
}
