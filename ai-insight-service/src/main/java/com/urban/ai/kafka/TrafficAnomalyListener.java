package com.urban.ai.kafka;

import com.urban.ai.rag.UrbanDataRetriever;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
@RequiredArgsConstructor
@Slf4j
public class TrafficAnomalyListener {

    private final UrbanDataRetriever retriever;

    @KafkaListener(topics = "traffic.anomalies", groupId = "ai-insight-service",
            containerFactory = "kafkaListenerContainerFactory")
    public void onAnomaly(Map<String, Object> anomaly) {
        try {
            retriever.indexAnomalyDocument(anomaly);
        } catch (Exception e) {
            log.warn("Failed to index anomaly on write: {}", e.getMessage());
        }
    }
}
