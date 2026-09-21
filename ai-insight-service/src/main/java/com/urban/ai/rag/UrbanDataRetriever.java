package com.urban.ai.rag;

import com.urban.ai.client.UrbanDataClient;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.EmbeddingStore;
import lombok.RequiredArgsConstructor;
import lombok.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
public class UrbanDataRetriever {

    private final UrbanDataClient dataClient;
    private final EmbeddingModel embeddingModel;
    private final com.urban.ai.metrics.LlmCallMetrics llmCallMetrics;
    private final EmbeddingStore<TextSegment> embeddingStore;

    private static final int CANDIDATE_POOL_SIZE = 20;
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
        Map<String, String> metadataMap = new HashMap<>();
        metadataMap.put("zone", zone);
        metadataMap.put("type", type);
        metadataMap.put("sourceId", sourceId);
        metadataMap.put("indexedAt", Instant.now().toString());

        Document document = Document.from(text, Metadata.from(metadataMap));
        TextSegment segment = TextSegment.from(document.text(), document.metadata());
        Embedding embedding = llmCallMetrics.time("embed_index", () -> embeddingModel.embed(segment).content());
        upsert(deterministicId(type, sourceId), embedding, segment);
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

    private String deterministicId(String type, String sourceId) {
        return type + ":" + sourceId;
    }

    private void upsert(String id, Embedding embedding, TextSegment segment) {
        try {
            embeddingStore.remove(id);
        } catch (Exception e) {
        }
        try {
            embeddingStore.addAll(List.of(id), List.of(embedding), List.of(segment));
        } catch (Exception e) {
            embeddingStore.add(embedding, segment);
        }
    }

    public List<RetrievedSegment> retrieve(String question, String zoneFilter, int topK) {
        return retrieve(question, zoneFilter, null, null, topK);
    }

    public List<RetrievedSegment> retrieve(String question, String zoneFilter, String typeFilter, String excludeSourceId, int topK) {
        Embedding queryEmbedding = llmCallMetrics.time("embed_query", () -> embeddingModel.embed(question).content());

        EmbeddingSearchRequest request = EmbeddingSearchRequest.builder()
                .queryEmbedding(queryEmbedding)
                .maxResults(CANDIDATE_POOL_SIZE)
                .minScore(MIN_VECTOR_SCORE)
                .build();

        EmbeddingSearchResult<TextSegment> result = embeddingStore.search(request);
        Set<String> queryTerms = tokenize(question);
        Instant now = Instant.now();

        List<RetrievedSegment> candidates = new ArrayList<>();
        for (EmbeddingMatch<TextSegment> match : result.matches()) {
            TextSegment segment = match.embedded();
            Metadata metadata = segment.metadata();

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

            double vectorScore = match.score();
            double keywordScore = keywordOverlap(queryTerms, tokenize(segment.text()));
            double recencyScore = recencyScore(safe(metadata, "indexedAt"), now);
            double combined = VECTOR_WEIGHT * vectorScore + KEYWORD_WEIGHT * keywordScore + RECENCY_WEIGHT * recencyScore;

            candidates.add(new RetrievedSegment(
                    sourceId,
                    segment.text(),
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

    private String safe(Metadata metadata, String key) {
        try {
            String v = metadata.get(key);
            return v != null ? v : "";
        } catch (Exception e) {
            return "";
        }
    }

    private double toDouble(Object o) {
        if (o instanceof Number n) return n.doubleValue();
        return 0.0;
    }
}
