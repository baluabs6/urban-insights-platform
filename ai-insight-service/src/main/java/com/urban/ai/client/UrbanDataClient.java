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

/**
 * Talks to the traffic-service and complaint-service over HTTP for anything not
 * already delivered via Kafka (see the kafka.consumer package for the
 * event-driven index-on-write path). Each call is wrapped in Resilience4j
 * @Retry (a couple of quick retries for transient blips) INSIDE @CircuitBreaker
 * (which opens after sustained failure and fails fast) — config in
 * application.yml under resilience4j.retry / resilience4j.circuitbreaker.
 */
@Component
@Slf4j
public class UrbanDataClient {

    private final WebClient trafficClient;
    private final WebClient complaintClient;

    /** Hard ceiling on how many pages a sweep will walk, so a runaway backlog can't spin forever. */
    private static final int MAX_SWEEP_PAGES = 50;
    private static final int SWEEP_PAGE_SIZE = 100;

    public UrbanDataClient(WebClient.Builder builder,
                            @Value("${services.traffic-service.url:http://localhost:8081}") String trafficUrl,
                            @Value("${services.complaint-service.url:http://localhost:8082}") String complaintUrl,
                            @Value("${security.admin-api-key:change-me-admin-in-prod}") String adminApiKey) {
        // Uses the ADMIN key: traffic-service/complaint-service now gate PII-exposing
        // and privileged endpoints to the admin tier, and this is a trusted
        // internal service, not a public caller.
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

    /**
     * complaint-service's /zone endpoint returns a Spring Data Page. This is
     * deliberately capped to ONE bounded page (not fully paged through) — it
     * feeds directly into RAG/LLM prompts, where more data is actively harmful
     * (context-window bloat, dilution of relevance), unlike the sweep methods
     * below where completeness correctness matters more than prompt size.
     */
    @SuppressWarnings("unchecked")
    @Retry(name = "complaintService")
    @CircuitBreaker(name = "complaintService", fallbackMethod = "zoneComplaintsFallback")
    public List<Map<String, Object>> getZoneComplaints(String zone) {
        Map<String, Object> page = complaintClient.get()
                .uri(uriBuilder -> uriBuilder.path("/api/complaints/zone/{zone}")
                        .queryParam("page", 0)
                        .queryParam("size", 25) // bounded on purpose — see javadoc
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

    /** Anomalies restricted to a single zone — used by the anomaly-explanation feature. */
    public List<Map<String, Object>> getRecentAnomaliesForZone(String zone) {
        return getRecentAnomalies().stream()
                .filter(a -> zone.equalsIgnoreCase(String.valueOf(a.get("zone"))))
                .toList();
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

    /**
     * SLA and reclassification sweeps need EVERY matching complaint, not just
     * page 1 — the previous version capped at size=50 and silently ignored
     * anything beyond that, meaning a city with a real backlog would have
     * breaches and mis-classifications that were never checked, forever, with
     * no error or log to say so. This walks pages until either an empty page
     * is returned or the safety cap (MAX_SWEEP_PAGES) is hit — if the cap is
     * hit, that IS logged, so it's a visible limitation instead of a silent one.
     */
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
        return null; // fetchAllPages treats null/empty content as end-of-results
    }

    /** Same "walk every page" fix applied to the reclassification sweep. */
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

    /** Callback used after async classification/duplicate-check completes (see ComplaintCreatedListener). */
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
