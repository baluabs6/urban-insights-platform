package com.urban.traffic.kafka;

import com.urban.traffic.dto.SensorReadingRequest;
import com.urban.traffic.entity.TrafficSensorReading;
import com.urban.traffic.service.TrafficIngestionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import static com.urban.traffic.config.KafkaProducerConfig.TOPIC_ANOMALIES;

/**
 * Consumes sensor readings off Kafka (published by TrafficController) and does
 * the actual DB write + anomaly scoring here, on Kafka's own consumer threads —
 * NOT on the servlet request thread. This is what makes ingestion genuinely
 * scale with sensor-fleet throughput instead of one blocking POST at a time.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SensorReadingConsumer {

    private final TrafficIngestionService ingestionService;
    private final KafkaTemplate<String, Object> kafkaTemplate;

    @KafkaListener(topics = "traffic.readings", groupId = "traffic-service-ingestion",
            containerFactory = "kafkaListenerContainerFactory")
    public void onReading(SensorReadingRequest request) {
        try {
            TrafficSensorReading saved = ingestionService.ingest(request);
            if (Boolean.TRUE.equals(saved.getAnomaly())) {
                // Publish downstream so ai-insight-service can index it into the RAG
                // vector store immediately (index-on-write) instead of waiting for
                // someone to ask a question about this zone.
                kafkaTemplate.send(TOPIC_ANOMALIES, saved.getZone(), saved);
            }
        } catch (Exception e) {
            // A single bad message must not take down the consumer thread / stall the partition.
            log.error("Failed to process sensor reading for sensor {}: {}", request.getSensorId(), e.getMessage(), e);
        }
    }
}
