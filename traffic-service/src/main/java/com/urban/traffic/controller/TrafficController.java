package com.urban.traffic.controller;

import com.urban.traffic.config.KafkaProducerConfig;
import com.urban.traffic.dto.SensorReadingRequest;
import com.urban.traffic.entity.TrafficSensorReading;
import com.urban.traffic.service.TrafficIngestionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/traffic")
@RequiredArgsConstructor
@Slf4j
public class TrafficController {

    private final TrafficIngestionService service;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final com.urban.traffic.service.ForecastingService forecastingService;

    @PostMapping("/ingest")
    public ResponseEntity<?> ingest(@Valid @RequestBody SensorReadingRequest request) {
        try {
            kafkaTemplate.send(KafkaProducerConfig.TOPIC_SENSOR_READINGS, request.getSensorId(), request)
                    .whenComplete((result, ex) -> {
                        if (ex != null) {
                            log.error("Async publish failed for sensor {} after send() returned: {}",
                                    request.getSensorId(), ex.getMessage(), ex);
                        }
                    });
        } catch (Exception e) {
            log.error("Synchronous publish failure for sensor {}, falling back to direct ingest: {}",
                    request.getSensorId(), e.getMessage(), e);
            TrafficSensorReading saved = service.ingest(request);
            return ResponseEntity.ok(Map.of("status", "accepted-degraded-sync", "reading", saved));
        }
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(Map.of("status", "accepted", "sensorId", request.getSensorId()));
    }

    @PostMapping("/ingest-sync")
    public ResponseEntity<TrafficSensorReading> ingestSync(@Valid @RequestBody SensorReadingRequest request) {
        return ResponseEntity.ok(service.ingest(request));
    }

    @GetMapping("/sensors/{sensorId}/latest")
    public ResponseEntity<TrafficSensorReading> latest(@PathVariable String sensorId) {
        TrafficSensorReading reading = service.getLatest(sensorId);
        return reading != null ? ResponseEntity.ok(reading) : ResponseEntity.notFound().build();
    }

    @GetMapping("/zones/{zone}/summary")
    public ResponseEntity<Map<String, Object>> zoneSummary(@PathVariable String zone) {
        return ResponseEntity.ok(service.getZoneSummary(zone));
    }

    @GetMapping("/anomalies")
    public ResponseEntity<Page<TrafficSensorReading>> anomalies(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(service.recentAnomalies(PageRequest.of(page, Math.min(size, 100))));
    }

    @GetMapping("/zones/{zone}/forecast")
    public ResponseEntity<com.urban.traffic.service.ForecastingService.ZoneForecast> zoneForecast(
            @PathVariable String zone) {
        return ResponseEntity.ok(forecastingService.getForecast(zone));
    }

    @PostMapping("/cache/evict")
    public ResponseEntity<Void> evictCache(@RequestHeader(value = "X-Caller-Id", required = false) String callerId) {
        log.info("AUDIT action=evictCache caller={}", callerId != null ? callerId : "unknown");
        service.evictAllCaches();
        return ResponseEntity.noContent().build();
    }
}
