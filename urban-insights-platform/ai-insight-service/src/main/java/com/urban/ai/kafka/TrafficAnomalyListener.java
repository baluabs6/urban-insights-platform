package com.urban.ai.kafka;

import com.urban.ai.rag.UrbanDataRetriever;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Consumes "traffic.anomalies" (published by traffic-service the moment its
 * z-score detector flags a reading) and indexes it into the RAG vector store
 * immediately — so "why is it noisy in Koramangala right now?" is grounded in
 * an anomaly that happened seconds ago, not whatever was last pulled the last
 * time someone asked a question about that zone.
 */
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
