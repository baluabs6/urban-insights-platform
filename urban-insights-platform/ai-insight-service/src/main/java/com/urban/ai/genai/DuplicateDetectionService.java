package com.urban.ai.genai;

import com.urban.ai.client.UrbanDataClient;
import com.urban.ai.dto.GenAiDtos.DuplicateCheckRequest;
import com.urban.ai.dto.GenAiDtos.DuplicateCheckResponse;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.model.embedding.EmbeddingModel;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Embeds the incoming complaint text and compares it against existing open
 * complaints in the same zone (fetched live from complaint-service) using
 * cosine similarity, so "pothole outside gate 3" and "big pothole near the
 * main gate" get flagged as the same underlying issue instead of two tickets.
 */
@Service
@RequiredArgsConstructor
public class DuplicateDetectionService {

    private final UrbanDataClient dataClient;
    private final EmbeddingModel embeddingModel;

    private static final double DUPLICATE_THRESHOLD = 0.85;

    public DuplicateCheckResponse check(DuplicateCheckRequest request) {
        List<Map<String, Object>> existing = dataClient.getZoneComplaints(request.getZone());
        Embedding newEmbedding = embeddingModel.embed(request.getDescription()).content();

        List<String> similar = new ArrayList<>();
        double maxScore = 0.0;

        for (Map<String, Object> complaint : existing) {
            Object descObj = complaint.get("description");
            if (descObj == null) continue;
            String existingDescription = String.valueOf(descObj);

            Embedding existingEmbedding = embeddingModel.embed(existingDescription).content();
            double score = cosineSimilarity(newEmbedding.vector(), existingEmbedding.vector());

            if (score > maxScore) maxScore = score;
            if (score >= DUPLICATE_THRESHOLD) {
                similar.add(existingDescription);
            }
        }

        return DuplicateCheckResponse.builder()
                .duplicate(!similar.isEmpty())
                .maxSimilarityScore(maxScore)
                .similarComplaints(similar)
                .build();
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
