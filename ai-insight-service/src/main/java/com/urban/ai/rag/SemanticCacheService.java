package com.urban.ai.rag;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.model.embedding.EmbeddingModel;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Component
@RequiredArgsConstructor
@Slf4j
public class SemanticCacheService {

    private final RedisTemplate<String, Object> redisTemplate;
    private final EmbeddingModel embeddingModel;
    private final com.urban.ai.metrics.LlmCallMetrics llmCallMetrics;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final double SIMILARITY_THRESHOLD = 0.94;
    private static final int MAX_ENTRIES_PER_ZONE = 30;
    private static final Duration TTL = Duration.ofMinutes(30);

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CachedEntry {
        private String question;
        private String answer;
        private float[] embedding;
    }

    public Optional<CachedEntry> lookup(String zone, String question) {
        String key = cacheKey(zone);
        List<CachedEntry> entries = readEntries(key);
        if (entries.isEmpty()) return Optional.empty();

        float[] queryVector = llmCallMetrics.time("embed_semantic_cache_lookup",
                () -> embeddingModel.embed(question).content().vector());

        CachedEntry best = null;
        double bestScore = -1;
        for (CachedEntry entry : entries) {
            double score = cosineSimilarity(queryVector, entry.getEmbedding());
            if (score > bestScore) {
                bestScore = score;
                best = entry;
            }
        }

        if (best != null && bestScore >= SIMILARITY_THRESHOLD) {
            log.info("Semantic cache hit for zone {} (similarity={})", zone, String.format("%.3f", bestScore));
            return Optional.of(best);
        }
        return Optional.empty();
    }

    public void store(String zone, String question, String answer) {
        Embedding embedding = llmCallMetrics.time("embed_semantic_cache_store", () -> embeddingModel.embed(question).content());
        CachedEntry entry = new CachedEntry(question, answer, embedding.vector());

        String key = cacheKey(zone);
        List<CachedEntry> entries = readEntries(key);
        entries.add(0, entry);
        if (entries.size() > MAX_ENTRIES_PER_ZONE) {
            entries = entries.subList(0, MAX_ENTRIES_PER_ZONE);
        }

        try {
            redisTemplate.opsForValue().set(key, objectMapper.writeValueAsString(entries), TTL);
        } catch (Exception e) {
            log.warn("Failed to write semantic cache for zone {}: {}", zone, e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private List<CachedEntry> readEntries(String key) {
        try {
            Object raw = redisTemplate.opsForValue().get(key);
            if (raw == null) return new ArrayList<>();
            String json = raw instanceof String s ? s : objectMapper.writeValueAsString(raw);
            CachedEntry[] arr = objectMapper.readValue(json, CachedEntry[].class);
            List<CachedEntry> list = new ArrayList<>();
            for (CachedEntry e : arr) list.add(e);
            return list;
        } catch (Exception e) {
            log.warn("Failed to read semantic cache: {}", e.getMessage());
            return new ArrayList<>();
        }
    }

    private String cacheKey(String zone) {
        return "semantic-cache:" + zone.toLowerCase();
    }

    private double cosineSimilarity(float[] a, float[] b) {
        double dot = 0, normA = 0, normB = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        if (normA == 0 || normB == 0) return 0;
        return dot / (Math.sqrt(normA) * Math.sqrt(normB));
    }
}
