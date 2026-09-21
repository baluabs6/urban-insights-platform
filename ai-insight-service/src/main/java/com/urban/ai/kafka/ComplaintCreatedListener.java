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

@Component
@RequiredArgsConstructor
@Slf4j
public class ComplaintCreatedListener {

    private final ComplaintClassificationService classificationService;
    private final DuplicateDetectionService duplicateDetectionService;
    private final PhotoVerificationService photoVerificationService;
    private final SentimentUrgencyService sentimentUrgencyService;
    private final UrbanDataRetriever retriever;
    private final UrbanDataClient dataClient;

    @KafkaListener(topics = "complaint.created", groupId = "ai-insight-service",
            containerFactory = "kafkaListenerContainerFactory")
    public void onComplaintCreated(Map<String, Object> complaint) {
        String id = String.valueOf(complaint.get("id"));
        String zone = String.valueOf(complaint.get("zone"));
        String description = String.valueOf(complaint.get("description"));

        try {
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
        dupRequest.setExcludeComplaintId(id);
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

        Object photoUrlsObj = complaint.get("photoUrls");
        if (photoUrlsObj instanceof List<?> photoUrls && !photoUrls.isEmpty()) {
            try {
                com.urban.ai.dto.GenAiDtos.PhotoVerificationRequest photoRequest =
                        new com.urban.ai.dto.GenAiDtos.PhotoVerificationRequest();
                photoRequest.setComplaintId(id);
                photoRequest.setCategory(classification.getCategory());
                photoRequest.setPhotoUrls(photoUrls.stream().map(String::valueOf).toList());
                var photoResult = photoVerificationService.verify(photoRequest);
                update.put("photoVerified", photoResult.isVerified());
                update.put("photoVerificationNote", photoResult.getNote());
            } catch (Exception e) {
                log.warn("Photo verification failed for complaint {}: {}", id, e.getMessage());
            }
        }

        try {
            com.urban.ai.dto.GenAiDtos.SentimentRequest sentimentRequest = new com.urban.ai.dto.GenAiDtos.SentimentRequest();
            sentimentRequest.setDescription(description);
            var sentimentResult = sentimentUrgencyService.score(sentimentRequest);
            update.put("sentimentUrgencyScore", sentimentResult.getSentimentUrgencyScore());
            update.put("sentimentSummary", sentimentResult.getSummary());
        } catch (Exception e) {
            log.warn("Sentiment scoring failed for complaint {}: {}", id, e.getMessage());
        }

        try {
            dataClient.patchClassification(id, update);
        } catch (Exception e) {
            log.warn("Failed to PATCH classification for complaint {}: {}. Will retry via sweep.", id, e.getMessage());
        }
    }
}
