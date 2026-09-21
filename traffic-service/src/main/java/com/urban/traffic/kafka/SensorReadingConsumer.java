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
                kafkaTemplate.send(TOPIC_ANOMALIES, saved.getZone(), saved);
            }
        } catch (Exception e) {
            log.error("Failed to process sensor reading for sensor {}: {}", request.getSensorId(), e.getMessage(), e);
        }
    }
}
