package com.urban.ai.client;

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Talks to the traffic-service and complaint-service over HTTP to pull the
 * freshest data at query time — this is the "R" (retrieval) source for RAG,
 * as opposed to a static document corpus.
 *
 * Each call is wrapped in a Resilience4j circuit breaker (config in
 * application.yml under resilience4j.circuitbreaker.instances). If a
 * downstream service is failing repeatedly, the breaker opens and requests
 * fail fast to the fallback (empty result) instead of piling up blocked
 * threads waiting on a dead dependency.
 */
@Component
@Slf4j
public class UrbanDataClient {

    private final WebClient trafficClient;
    private final WebClient complaintClient;

    public UrbanDataClient(WebClient.Builder builder,
                            @Value("${services.traffic-service.url:http://localhost:8081}") String trafficUrl,
                            @Value("${services.complaint-service.url:http://localhost:8082}") String complaintUrl) {
        this.trafficClient = builder.baseUrl(trafficUrl).build();
        this.complaintClient = builder.baseUrl(complaintUrl).build();
    }

    @CircuitBreaker(name = "trafficService", fallbackMethod = "zoneTrafficSummaryFallback")
    public Map<String, Object> getZoneTrafficSummary(String zone) {
        return trafficClient.get()
                .uri("/api/traffic/zones/{zone}/summary", zone)
                .retrieve()
                .bodyToMono(Map.class)
                .timeout(Duration.ofSeconds(3))
                .block();
    }

    @SuppressWarnings("unused")
    private Map<String, Object> zoneTrafficSummaryFallback(String zone, Throwable t) {
        log.warn("traffic-service circuit open/unavailable for zone {}: {}", zone, t.getMessage());
        return Map.of();
    }

    @SuppressWarnings("unchecked")
    @CircuitBreaker(name = "complaintService", fallbackMethod = "zoneComplaintsFallback")
    public List<Map<String, Object>> getZoneComplaints(String zone) {
        return complaintClient.get()
                .uri("/api/complaints/zone/{zone}", zone)
                .retrieve()
                .bodyToMono(List.class)
                .timeout(Duration.ofSeconds(3))
                .block();
    }

    @SuppressWarnings("unused")
    private List<Map<String, Object>> zoneComplaintsFallback(String zone, Throwable t) {
        log.warn("complaint-service circuit open/unavailable for zone {}: {}", zone, t.getMessage());
        return List.of();
    }

    @SuppressWarnings("unchecked")
    @CircuitBreaker(name = "trafficService", fallbackMethod = "recentAnomaliesFallback")
    public List<Map<String, Object>> getRecentAnomalies() {
        return trafficClient.get()
                .uri("/api/traffic/anomalies")
                .retrieve()
                .bodyToMono(List.class)
                .timeout(Duration.ofSeconds(3))
                .block();
    }

    @SuppressWarnings("unused")
    private List<Map<String, Object>> recentAnomaliesFallback(Throwable t) {
        log.warn("traffic-service circuit open/unavailable for anomalies: {}", t.getMessage());
        return List.of();
    }

    /** Anomalies restricted to a single zone — used by the anomaly-explanation feature. */
    public List<Map<String, Object>> getRecentAnomaliesForZone(String zone) {
        return getRecentAnomalies().stream()
                .filter(a -> zone.equalsIgnoreCase(String.valueOf(a.get("zone"))))
                .toList();
    }

    @SuppressWarnings("unchecked")
    @CircuitBreaker(name = "complaintService", fallbackMethod = "topUrgentComplaintsFallback")
    public List<Map<String, Object>> getTopUrgentComplaints() {
        return complaintClient.get()
                .uri("/api/complaints/urgent")
                .retrieve()
                .bodyToMono(List.class)
                .timeout(Duration.ofSeconds(3))
                .block();
    }

    @SuppressWarnings("unused")
    private List<Map<String, Object>> topUrgentComplaintsFallback(Throwable t) {
        log.warn("complaint-service circuit open/unavailable for urgent complaints: {}", t.getMessage());
        return List.of();
    }

    @CircuitBreaker(name = "complaintService", fallbackMethod = "complaintByIdFallback")
    public Map<String, Object> getComplaintById(String id) {
        return complaintClient.get()
                .uri("/api/complaints/{id}", id)
                .retrieve()
                .bodyToMono(Map.class)
                .timeout(Duration.ofSeconds(3))
                .block();
    }

    @SuppressWarnings("unused")
    private Map<String, Object> complaintByIdFallback(String id, Throwable t) {
        log.warn("complaint-service circuit open/unavailable for complaint {}: {}", id, t.getMessage());
        return null;
    }

    @SuppressWarnings("unchecked")
    @CircuitBreaker(name = "complaintService", fallbackMethod = "complaintsByStatusFallback")
    public List<Map<String, Object>> getComplaintsByStatus(String status) {
        return complaintClient.get()
                .uri("/api/complaints/status/{status}", status)
                .retrieve()
                .bodyToMono(List.class)
                .timeout(Duration.ofSeconds(3))
                .block();
    }

    @SuppressWarnings("unused")
    private List<Map<String, Object>> complaintsByStatusFallback(String status, Throwable t) {
        log.warn("complaint-service circuit open/unavailable for status {}: {}", status, t.getMessage());
        return List.of();
    }
}
