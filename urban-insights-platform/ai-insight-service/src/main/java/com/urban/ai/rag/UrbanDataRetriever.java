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

/**
 * The "Retrieval" half of RAG — now with:
 *  - Source-tagged segments (id/zone/type/timestamp) so answers can cite what backs them.
 *  - Hybrid scoring: vector similarity blended with simple keyword overlap, so exact
 *    terms (sensor IDs, category names) aren't lost to pure semantic search.
 *  - Recency weighting: fresher sensor readings/complaints are favored over stale ones
 *    with the same similarity score.
 *  - Metadata (zone) filtering applied client-side after an over-fetch, since we don't
 *    assume the underlying store supports server-side filters.
 */
@Component
@RequiredArgsConstructor
public class UrbanDataRetriever {

    private final UrbanDataClient dataClient;
    private final EmbeddingModel embeddingModel;
    private final EmbeddingStore<TextSegment> embeddingStore;

    private static final int CANDIDATE_POOL_SIZE = 20; // over-fetch before re-ranking
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

    /** Refresh the vector index for a zone right before answering — keeps RAG grounded in "now". */
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
        Embedding embedding = embeddingModel.embed(segment).content();
        upsert(deterministicId(type, sourceId), embedding, segment);
    }

    /**
     * Event-driven upsert used by the Kafka listeners (ComplaintCreatedListener,
     * TrafficAnomalyListener) — this is what replaces "re-embed everything on
     * every RAG query" with index-on-write: data is embedded once, when it
     * changes, not once per question asked about it.
     */
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
     * Deterministic ID so re-indexing the same logical record (e.g. a
     * complaint's status changing) updates the existing vector instead of
     * appending a duplicate that grows the index forever.
     */
    private String deterministicId(String type, String sourceId) {
        return type + ":" + sourceId;
    }

    /** Best-effort upsert: not every EmbeddingStore implementation supports remove(); tolerate it if not. */
    private void upsert(String id, Embedding embedding, TextSegment segment) {
        try {
            embeddingStore.remove(id);
        } catch (Exception e) {
            // Store doesn't support remove(), or the id didn't exist yet — either way, proceed to add.
        }
        try {
            embeddingStore.addAll(List.of(id), List.of(embedding), List.of(segment));
        } catch (Exception e) {
            // Fallback for stores whose addAll(ids,...) overload isn't available — assigns its own id,
            // which means this particular record won't dedupe on re-index, but indexing still succeeds.
            embeddingStore.add(embedding, segment);
        }
    }

    /**
     * Retrieve the most relevant, freshest, citeable context for a natural-language
     * question, optionally scoped to a single zone.
     *
     * @param question free-text question (or a rewritten sub-query)
     * @param zoneFilter if non-null, only segments tagged with this zone are considered
     * @param topK      how many segments to return after re-ranking (adaptive per query type)
     */
    public List<RetrievedSegment> retrieve(String question, String zoneFilter, int topK) {
        return retrieve(question, zoneFilter, null, null, topK);
    }

    /**
     * Full form used by duplicate detection: restrict to a single document
     * "type" (e.g. only "complaint" segments, not traffic summaries/anomalies)
     * and exclude one specific sourceId (a complaint checking for duplicates
     * against others must not match its own just-indexed copy of itself).
     */
    public List<RetrievedSegment> retrieve(String question, String zoneFilter, String typeFilter, String excludeSourceId, int topK) {
        Embedding queryEmbedding = embeddingModel.embed(question).content();

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
                continue; // client-side metadata filter
            }
            String type = safe(metadata, "type");
            if (typeFilter != null && !typeFilter.equalsIgnoreCase(type)) {
                continue;
            }
            String sourceId = safe(metadata, "sourceId");
            if (excludeSourceId != null && excludeSourceId.equalsIgnoreCase(sourceId)) {
                continue; // don't let a record match its own just-indexed copy
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

    /** Convenience overload matching the previous simple-string API, kept for compatibility. */
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
        // Exponential decay: score = 0.5 ^ (age / half-life) -> 1.0 when fresh, ~0.5 at half-life.
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
