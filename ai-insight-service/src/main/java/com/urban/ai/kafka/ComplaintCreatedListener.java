package com.urban.ai.kafka;

import com.urban.ai.client.UrbanDataClient;
import com.urban.ai.dto.GenAiDtos.ClassifyRequest;
import com.urban.ai.dto.GenAiDtos.ClassifyResponse;
import com.urban.ai.dto.GenAiDtos.DuplicateCheckRequest;
import com.urban.ai.dto.GenAiDtos.DuplicateCheckResponse;
import com.urban.ai.genai.ComplaintClassificationService;
import com.urban.ai.genai.DuplicateDetectionService;
import com.urban.ai.rag.UrbanDataRetriever;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * Consumes "complaint.created" (published by complaint-service the instant a
 * citizen submits) and does the real work asynchronously:
 *   1. LLM classification (was previously a synchronous call blocking the
 *      citizen's submit request — now happens here, off the request path).
 *   2. Embedding-based duplicate check against other open complaints.
 *   3. Index-on-write into the RAG vector store (replaces re-embedding the
 *      whole zone on every question asked).
 *   4. PATCH the real classification back to complaint-service.
 *
 * If any step fails, the complaint keeps its fast heuristic classification
 * (set at submit time) and gets picked up again by ReclassificationSweep.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ComplaintCreatedListener {

    private final ComplaintClassificationService classificationService;
    private final DuplicateDetectionService duplicateDetectionService;
    private final UrbanDataRetriever retriever;
    private final UrbanDataClient dataClient;

    @KafkaListener(topics = "complaint.created", groupId = "ai-insight-service",
            containerFactory = "kafkaListenerContainerFactory")
    public void onComplaintCreated(Map<String, Object> complaint) {
        String id = String.valueOf(complaint.get("id"));
        String zone = String.valueOf(complaint.get("zone"));
        String description = String.valueOf(complaint.get("description"));

        try {
            // Index immediately so RAG queries can see this complaint right away,
            // even before classification finishes.
            retriever.indexComplaintDocument(complaint);
        } catch (Exception e) {
            log.warn("Failed to index complaint {} on write: {}", id, e.getMessage());
        }

        ClassifyRequest classifyRequest = new ClassifyRequest();
        classifyRequest.setDescription(description);
        classifyRequest.setZone(zone);
        ClassifyResponse classification = classificationService.classify(classifyRequest);

        DuplicateCheckRequest dupRequest = new DuplicateCheckRequest();
        dupRequest.setDescription(description);
        dupRequest.setZone(zone);
        dupRequest.setExcludeComplaintId(id); // this complaint was already indexed above — don't match itself
        DuplicateCheckResponse duplicate;
        try {
            duplicate = duplicateDetectionService.check(dupRequest);
        } catch (Exception e) {
            log.warn("Duplicate check failed for complaint {}: {}", id, e.getMessage());
            duplicate = null;
        }

        Map<String, Object> update = new HashMap<>();
        update.put("category", classification.getCategory());
        update.put("department", classification.getDepartment());
        update.put("urgencyScore", classification.getUrgencyScore());
        update.put("tags", classification.getTags());
        update.put("classificationSource", classification.getSource());
        if (duplicate != null) {
            update.put("likelyDuplicate", duplicate.isDuplicate());
            update.put("duplicateSimilarityScore", duplicate.getMaxSimilarityScore());
            update.put("similarComplaintDescriptions", duplicate.getSimilarComplaints());
        }

        try {
            dataClient.patchClassification(id, update);
        } catch (Exception e) {
            log.warn("Failed to PATCH classification for complaint {}: {}. Will retry via sweep.", id, e.getMessage());
        }
    }
}
