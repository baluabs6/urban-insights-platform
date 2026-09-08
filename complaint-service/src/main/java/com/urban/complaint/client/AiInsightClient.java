package com.urban.complaint.client;

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.List;

/**
 * Calls the ai-insight-service's GenAI endpoints at complaint-submission time.
 * Wrapped in a Resilience4j circuit breaker: if ai-insight-service is down or
 * slow, the breaker opens after enough failures and calls fail straight to the
 * fallback (null) without waiting on the timeout each time — ComplaintService
 * then falls back to its local heuristic, so submissions never block on AI.
 */
@Component
@Slf4j
public class AiInsightClient {

    private final WebClient client;

    public AiInsightClient(WebClient.Builder builder,
                            @Value("${services.ai-insight-service.url:http://localhost:8083}") String aiUrl) {
        this.client = builder.baseUrl(aiUrl).build();
    }

    @CircuitBreaker(name = "aiInsightService", fallbackMethod = "classifyFallback")
    public ClassifyResult classify(String description, String zone) {
        return client.post()
                .uri("/api/ai/classify-complaint")
                .bodyValue(new ClassifyRequestBody(description, zone))
                .retrieve()
                .bodyToMono(ClassifyResult.class)
                .timeout(Duration.ofSeconds(5))
                .block();
    }

    @SuppressWarnings("unused")
    private ClassifyResult classifyFallback(String description, String zone, Throwable t) {
        log.warn("ai-insight-service circuit open/unavailable for classify: {}", t.getMessage());
        return null;
    }

    @CircuitBreaker(name = "aiInsightService", fallbackMethod = "duplicateCheckFallback")
    public DuplicateResult duplicateCheck(String description, String zone) {
        return client.post()
                .uri("/api/ai/duplicate-check")
                .bodyValue(new ClassifyRequestBody(description, zone))
                .retrieve()
                .bodyToMono(DuplicateResult.class)
                .timeout(Duration.ofSeconds(5))
                .block();
    }

    @SuppressWarnings("unused")
    private DuplicateResult duplicateCheckFallback(String description, String zone, Throwable t) {
        log.warn("ai-insight-service circuit open/unavailable for duplicate-check: {}", t.getMessage());
        return null;
    }

    private record ClassifyRequestBody(String description, String zone) {}

    @Data
    public static class ClassifyResult {
        private String category;
        private String department;
        private Double urgencyScore;
        private List<String> tags;
        private String reasoning;
    }

    @Data
    public static class DuplicateResult {
        private boolean duplicate;
        private double maxSimilarityScore;
        private List<String> similarComplaints;
    }
}
