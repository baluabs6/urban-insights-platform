package com.urban.ai.rag;

import com.urban.ai.client.UrbanDataClient;
import lombok.RequiredArgsConstructor;
import lombok.Value;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
public class UrbanDataRetriever {

    private final UrbanDataClient dataClient;
    private final com.urban.ai.metrics.LlmCallMetrics llmCallMetrics;
    /** Spring AI VectorStore (pgvector in production). Embeds on add() and on similaritySearch(). */
    private final VectorStore vectorStore;

    private static final int CANDIDATE_POOL_SIZE = 20;
    /**
     * Relevance floor on the 0..1 "relevance" scale, where relevance = (cosine + 1) / 2. This is the scale the
     * original thresholds (0.35 floor, 0.85 duplicate threshold) were tuned on. Spring AI's VectorStore returns
     * plain cosine similarity as the score, so it is converted in {@link #retrieve}.
     */
    private static final double MIN_VECTOR_SCORE = 0.35;
    private static final double VECTOR_WEIGHT = 0.65;
    private static final double KEYWORD_WEIGHT = 0.20;
    private static final double RECENCY_WEIGHT = 0.15;
    private static final double RECENCY_HALF_LIFE_HOURS = 12.0;

    @Value
    public static class RetrievedSegment {
        String id;
        String text;
        String zone;
        String type;
        Instant timestamp;
        double vectorScore;
        double keywordScore;
        double recencyScore;
        double combinedScore;
    }

    public void indexZone(String zone) {
        Map<String, Object> trafficSummary = dataClient.getZoneTrafficSummary(zone);
        List<Map<String, Object>> complaints = dataClient.getZoneComplaints(zone);

        if (trafficSummary != null && !trafficSummary.isEmpty()) {
            String text = String.format(
                    "Traffic/sensor summary for zone %s: average sensor reading is %.2f across %s readings, with %s flagged anomalies in the last 24 hours.",
                    trafficSummary.getOrDefault("zone", zone),
                    toDouble(trafficSummary.get("averageValue")),
                    trafficSummary.getOrDefault("readingCount", 0),
                    trafficSummary.getOrDefault("anomalyCount", 0)
            );
            indexSegment(text, zone, "traffic_summary", "summary-" + zone);
        }

        for (Map<String, Object> complaint : complaints) {
            String text = String.format(
                    "Citizen complaint in zone %s: category=%s, status=%s, description=\"%s\", urgencyScore=%s.",
                    zone,
                    complaint.get("category"),
                    complaint.get("status"),
                    complaint.get("description"),
                    complaint.get("urgencyScore")
            );
            String complaintId = String.valueOf(complaint.getOrDefault("id", UUID.randomUUID().toString()));
            indexSegment(text, zone, "complaint", complaintId);
        }
    }

    private void indexSegment(String text, String zone, String type, String sourceId) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("zone", zone);
        metadata.put("type", type);
        metadata.put("sourceId", sourceId);
        metadata.put("indexedAt", Instant.now().toString());

        Document document = Document.builder()
                .id(deterministicId(type, sourceId))
                .text(text)
                .metadata(metadata)
                .build();
        upsert(document);
    }

    public void indexComplaintDocument(Map<String, Object> complaint) {
        String id = String.valueOf(complaint.get("id"));
        String zone = String.valueOf(complaint.get("zone"));
        String text = String.format(
                "Citizen complaint in zone %s: category=%s, status=%s, description=\"%s\", urgencyScore=%s.",
                zone, complaint.get("category"), complaint.get("status"), complaint.get("description"),
                complaint.get("urgencyScore"));
        indexSegment(text, zone, "complaint", id);
    }

    public void indexAnomalyDocument(Map<String, Object> anomaly) {
        String sensorId = String.valueOf(anomaly.get("sensorId"));
        String zone = String.valueOf(anomaly.get("zone"));
        String id = sensorId + "-" + anomaly.getOrDefault("recordedAt", Instant.now().toString());
        String text = String.format(
                "Sensor anomaly in zone %s: sensorId=%s, sensorType=%s, value=%s, anomalyScore=%s.",
                zone, sensorId, anomaly.get("sensorType"), anomaly.get("value"), anomaly.get("anomalyScore"));
        indexSegment(text, zone, "anomaly", id);
    }

    /**
     * Spring AI's pgvector table uses a UUID primary key, so the stable "type:sourceId" key is mapped to a
     * name-based UUID. Re-indexing the same complaint/summary therefore overwrites its previous embedding
     * instead of piling up duplicates.
     */
    private String deterministicId(String type, String sourceId) {
        return UUID.nameUUIDFromBytes((type + ":" + sourceId).getBytes(StandardCharsets.UTF_8)).toString();
    }

    private void upsert(Document document) {
        try {
            vectorStore.delete(List.of(document.getId()));
        } catch (Exception e) {
            // nothing to delete yet, or the store doesn't support it — add() below still upserts
        }
        llmCallMetrics.timeRunnable("embed_index", () -> vectorStore.add(List.of(document)));
    }

    public List<RetrievedSegment> retrieve(String question, String zoneFilter, int topK) {
        return retrieve(question, zoneFilter, null, null, topK);
    }

    public List<RetrievedSegment> retrieve(String question, String zoneFilter, String typeFilter, String excludeSourceId, int topK) {
        SearchRequest request = SearchRequest.builder()
                .query(question)
                .topK(CANDIDATE_POOL_SIZE)
                .similarityThreshold(SearchRequest.SIMILARITY_THRESHOLD_ACCEPT_ALL)
                .build();

        List<Document> matches = llmCallMetrics.time("embed_query", () -> vectorStore.similaritySearch(request));
        Set<String> queryTerms = tokenize(question);
        Instant now = Instant.now();

        List<RetrievedSegment> candidates = new ArrayList<>();
        for (Document match : matches == null ? List.<Document>of() : matches) {
            Map<String, Object> metadata = match.getMetadata();

            String zone = safe(metadata, "zone");
            if (zoneFilter != null && !zoneFilter.equalsIgnoreCase(zone)) {
                continue;
            }
            String type = safe(metadata, "type");
            if (typeFilter != null && !typeFilter.equalsIgnoreCase(type)) {
                continue;
            }
            String sourceId = safe(metadata, "sourceId");
            if (excludeSourceId != null && excludeSourceId.equalsIgnoreCase(sourceId)) {
                continue;
            }

            double cosine = match.getScore() != null ? match.getScore() : 0.0;
            double vectorScore = (cosine + 1.0) / 2.0;
            if (vectorScore < MIN_VECTOR_SCORE) {
                continue;
            }
            String text = match.getText() != null ? match.getText() : "";
            double keywordScore = keywordOverlap(queryTerms, tokenize(text));
            double recencyScore = recencyScore(safe(metadata, "indexedAt"), now);
            double combined = VECTOR_WEIGHT * vectorScore + KEYWORD_WEIGHT * keywordScore + RECENCY_WEIGHT * recencyScore;

            candidates.add(new RetrievedSegment(
                    sourceId,
                    text,
                    zone,
                    type,
                    parseInstantOrNow(safe(metadata, "indexedAt")),
                    vectorScore,
                    keywordScore,
                    recencyScore,
                    combined
            ));
        }

        return candidates.stream()
                .sorted(Comparator.comparingDouble(RetrievedSegment::getCombinedScore).reversed())
                .limit(topK)
                .collect(Collectors.toList());
    }

    public List<String> retrieveContext(String question) {
        return retrieve(question, null, 5).stream()
                .map(RetrievedSegment::getText)
                .collect(Collectors.toList());
    }

    private double keywordOverlap(Set<String> queryTerms, Set<String> segmentTerms) {
        if (queryTerms.isEmpty() || segmentTerms.isEmpty()) return 0.0;
        long overlap = queryTerms.stream().filter(segmentTerms::contains).count();
        return (double) overlap / queryTerms.size();
    }

    private Set<String> tokenize(String text) {
        return Arrays.stream(text.toLowerCase().replaceAll("[^a-z0-9\\s]", " ").split("\\s+"))
                .filter(t -> t.length() > 2)
                .collect(Collectors.toSet());
    }

    private double recencyScore(String indexedAtIso, Instant now) {
        Instant indexedAt = parseInstantOrNow(indexedAtIso);
        double hoursAge = Duration.between(indexedAt, now).toMinutes() / 60.0;
        return Math.pow(0.5, hoursAge / RECENCY_HALF_LIFE_HOURS);
    }

    private Instant parseInstantOrNow(String iso) {
        try {
            return Instant.parse(iso);
        } catch (Exception e) {
            return Instant.now();
        }
    }

    private String safe(Map<String, Object> metadata, String key) {
        Object v = metadata == null ? null : metadata.get(key);
        return v != null ? String.valueOf(v) : "";
    }

    private double toDouble(Object o) {
        if (o instanceof Number n) return n.doubleValue();
        return 0.0;
    }
}
