package com.urban.ai.kafka;

import com.urban.ai.genai.ClassificationFeedbackService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
@RequiredArgsConstructor
@Slf4j
public class ClassificationOverrideListener {

    private final ClassificationFeedbackService feedbackService;

    @KafkaListener(topics = "complaint.classification.overridden", groupId = "ai-insight-service",
            containerFactory = "kafkaListenerContainerFactory")
    public void onOverride(Map<String, Object> event) {
        try {
            feedbackService.recordCorrection(event);
        } catch (Exception e) {
            log.warn("Failed to record classification-override feedback for {}: {}",
                    event.get("complaintId"), e.getMessage());
        }
    }
}
