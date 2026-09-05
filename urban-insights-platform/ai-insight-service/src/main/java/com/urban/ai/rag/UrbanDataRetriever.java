package com.urban.ai.rag;

import com.urban.ai.client.UrbanDataClient;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.EmbeddingStore;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * The "Retrieval" half of RAG:
 *  1. Pull fresh structured data from traffic-service (Postgres/Redis-backed) and
 *     complaint-service (MongoDB-backed).
 *  2. Flatten it into short natural-language text segments ("documents").
 *  3. Embed each segment and upsert into the vector store, tagged by zone.
 *  4. At query time, embed the user's question and pull the top-k most similar
 *     segments to ground the LLM's answer instead of relying on its own memory.
 */
@Component
@RequiredArgsConstructor
public class UrbanDataRetriever {

    private final UrbanDataClient dataClient;
    private final EmbeddingModel embeddingModel;
    private final EmbeddingStore<TextSegment> embeddingStore;

    private static final int TOP_K = 5;
    private static final double MIN_SCORE = 0.5;

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
            indexSegment(text, zone, "traffic_summary");
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
            indexSegment(text, zone, "complaint");
        }
    }

    private void indexSegment(String text, String zone, String type) {
        Document document = Document.from(text, dev.langchain4j.data.document.Metadata.from(Map.of(
                "zone", zone, "type", type
        )));
        TextSegment segment = TextSegment.from(document.text(), document.metadata());
        Embedding embedding = embeddingModel.embed(segment).content();
        embeddingStore.add(embedding, segment);
    }

    /** Retrieve the most relevant grounded context for a natural-language question. */
    public List<String> retrieveContext(String question) {
        Embedding queryEmbedding = embeddingModel.embed(question).content();

        EmbeddingSearchRequest request = EmbeddingSearchRequest.builder()
                .queryEmbedding(queryEmbedding)
                .maxResults(TOP_K)
                .minScore(MIN_SCORE)
                .build();

        EmbeddingSearchResult<TextSegment> result = embeddingStore.search(request);

        return result.matches().stream()
                .map(EmbeddingMatch::embedded)
                .map(TextSegment::text)
                .collect(Collectors.toList());
    }

    private double toDouble(Object o) {
        if (o instanceof Number n) return n.doubleValue();
        return 0.0;
    }
}
