package com.urban.ai.client;

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.IntFunction;

@Component
@Slf4j
public class UrbanDataClient {

    private final WebClient trafficClient;
    private final WebClient complaintClient;

    private static final int MAX_SWEEP_PAGES = 50;
    private static final int SWEEP_PAGE_SIZE = 100;

    public UrbanDataClient(WebClient.Builder builder,
                            @Value("${services.traffic-service.url:http://localhost:8081}") String trafficUrl,
                            @Value("${services.complaint-service.url:http://localhost:8082}") String complaintUrl,
                            @Value("${security.admin-api-key:change-me-admin-in-prod}") String adminApiKey) {
        this.trafficClient = builder.baseUrl(trafficUrl).defaultHeader("X-API-Key", adminApiKey).build();
        this.complaintClient = builder.baseUrl(complaintUrl).defaultHeader("X-API-Key", adminApiKey).build();
    }

    @Retry(name = "trafficService")
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
    @Retry(name = "complaintService")
    @CircuitBreaker(name = "complaintService", fallbackMethod = "zoneComplaintsFallback")
    public List<Map<String, Object>> getZoneComplaints(String zone) {
        Map<String, Object> page = complaintClient.get()
                .uri(uriBuilder -> uriBuilder.path("/api/complaints/zone/{zone}")
                        .queryParam("page", 0)
                        .queryParam("size", 25)
                        .build(zone))
                .retrieve()
                .bodyToMono(Map.class)
                .timeout(Duration.ofSeconds(3))
                .block();
        Object content = page != null ? page.get("content") : null;
        return content instanceof List ? (List<Map<String, Object>>) content : List.of();
    }

    @SuppressWarnings("unused")
    private List<Map<String, Object>> zoneComplaintsFallback(String zone, Throwable t) {
        log.warn("complaint-service circuit open/unavailable for zone {}: {}", zone, t.getMessage());
        return List.of();
    }

    @SuppressWarnings("unchecked")
    @Retry(name = "trafficService")
    @CircuitBreaker(name = "trafficService", fallbackMethod = "recentAnomaliesFallback")
    public List<Map<String, Object>> getRecentAnomalies() {
        Map<String, Object> page = trafficClient.get()
                .uri(uriBuilder -> uriBuilder.path("/api/traffic/anomalies")
                        .queryParam("page", 0)
                        .queryParam("size", 50)
                        .build())
                .retrieve()
                .bodyToMono(Map.class)
                .timeout(Duration.ofSeconds(3))
                .block();
        Object content = page != null ? page.get("content") : null;
        return content instanceof List ? (List<Map<String, Object>>) content : List.of();
    }

    @SuppressWarnings("unused")
    private List<Map<String, Object>> recentAnomaliesFallback(Throwable t) {
        log.warn("traffic-service circuit open/unavailable for anomalies: {}", t.getMessage());
        return List.of();
    }

    public List<Map<String, Object>> getRecentAnomaliesForZone(String zone) {
        return getRecentAnomalies().stream()
                .filter(a -> zone.equalsIgnoreCase(String.valueOf(a.get("zone"))))
                .toList();
    }

    @SuppressWarnings("unchecked")
    @Retry(name = "trafficService")
    @CircuitBreaker(name = "trafficService", fallbackMethod = "zoneForecastFallback")
    public Map<String, Object> getZoneForecast(String zone) {
        return trafficClient.get()
                .uri("/api/traffic/zones/{zone}/forecast", zone)
                .retrieve()
                .bodyToMono(Map.class)
                .timeout(Duration.ofSeconds(3))
                .block();
    }

    @SuppressWarnings("unused")
    private Map<String, Object> zoneForecastFallback(String zone, Throwable t) {
        log.warn("traffic-service circuit open/unavailable for forecast of zone {}: {}", zone, t.getMessage());
        return Map.of();
    }

    @Retry(name = "complaintService")
    @CircuitBreaker(name = "complaintService", fallbackMethod = "topUrgentComplaintsFallback")
    @SuppressWarnings("unchecked")
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

    @Retry(name = "complaintService")
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

    public List<Map<String, Object>> getComplaintsByStatus(String status) {
        return fetchAllPages(page -> fetchStatusPage(status, page), "status:" + status);
    }

    @SuppressWarnings("unchecked")
    @Retry(name = "complaintService")
    @CircuitBreaker(name = "complaintService", fallbackMethod = "statusPageFallback")
    private Map<String, Object> fetchStatusPage(String status, int page) {
        return complaintClient.get()
                .uri(uriBuilder -> uriBuilder.path("/api/complaints/status/{status}")
                        .queryParam("page", page)
                        .queryParam("size", SWEEP_PAGE_SIZE)
                        .build(status))
                .retrieve()
                .bodyToMono(Map.class)
                .timeout(Duration.ofSeconds(5))
                .block();
    }

    @SuppressWarnings("unused")
    private Map<String, Object> statusPageFallback(String status, int page, Throwable t) {
        log.warn("complaint-service circuit open/unavailable for status {} page {}: {}", status, page, t.getMessage());
        return null;
    }

    public List<Map<String, Object>> getComplaintsNeedingReclassification() {
        return fetchAllPages(this::fetchReclassificationPage, "needing-reclassification");
    }

    @SuppressWarnings("unchecked")
    @Retry(name = "complaintService")
    @CircuitBreaker(name = "complaintService", fallbackMethod = "reclassificationPageFallback")
    private Map<String, Object> fetchReclassificationPage(int page) {
        return complaintClient.get()
                .uri(uriBuilder -> uriBuilder.path("/api/complaints/needing-reclassification")
                        .queryParam("page", page)
                        .queryParam("size", SWEEP_PAGE_SIZE)
                        .build())
                .retrieve()
                .bodyToMono(Map.class)
                .timeout(Duration.ofSeconds(5))
                .block();
    }

    @SuppressWarnings("unused")
    private Map<String, Object> reclassificationPageFallback(int page, Throwable t) {
        log.warn("complaint-service circuit open/unavailable for reclassification page {}: {}", page, t.getMessage());
        return null;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> fetchAllPages(IntFunction<Map<String, Object>> pageFetcher, String label) {
        List<Map<String, Object>> all = new ArrayList<>();
        for (int page = 0; page < MAX_SWEEP_PAGES; page++) {
            Map<String, Object> pageResult = pageFetcher.apply(page);
            Object content = pageResult != null ? pageResult.get("content") : null;
            List<Map<String, Object>> items = content instanceof List ? (List<Map<String, Object>>) content : List.of();
            if (items.isEmpty()) {
                return all;
            }
            all.addAll(items);

            Object last = pageResult.get("last");
            if (Boolean.TRUE.equals(last)) {
                return all;
            }
        }
        log.warn("Sweep for [{}] hit the {}-page safety cap ({} items collected) — there may be more unprocessed " +
                "records. Consider investigating the backlog size.", label, MAX_SWEEP_PAGES, all.size());
        return all;
    }

    @Retry(name = "complaintService")
    @CircuitBreaker(name = "complaintService", fallbackMethod = "patchClassificationFallback")
    public void patchClassification(String complaintId, Map<String, Object> update) {
        complaintClient.patch()
                .uri("/api/complaints/{id}/classification", complaintId)
                .bodyValue(update)
                .retrieve()
                .toBodilessEntity()
                .timeout(Duration.ofSeconds(5))
                .block();
    }

    @SuppressWarnings("unused")
    private void patchClassificationFallback(String complaintId, Map<String, Object> update, Throwable t) {
        log.warn("Failed to PATCH classification back to complaint-service for {}: {}. " +
                "It will be picked up again by the reclassification sweep.", complaintId, t.getMessage());
    }
}
