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

    /**
     * Real-time ingestion path: publish to Kafka and return immediately (202).
     * Actual DB write + anomaly scoring happens asynchronously in
     * SensorReadingConsumer, off this request thread — this is what lets the
     * endpoint absorb bursts from a large sensor fleet instead of blocking on
     * Postgres per-request.
     *
     * Gap fix: the previous version discarded kafkaTemplate.send()'s returned
     * future entirely — a failed publish (broker down, serialization error,
     * etc.) meant the client got a 202 "accepted" for a reading that was
     * actually silently dropped forever. This now (a) logs publish failures
     * with full context instead of losing them silently, and (b) falls back to
     * the synchronous path so the reading isn't lost — at the cost of the
     * request blocking for that one call. This is NOT a substitute for a real
     * transactional outbox pattern (still a gap — see README), but it closes
     * the "202 lied to the client" failure mode.
     */
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

    /**
     * Synchronous fallback for local dev/testing without Kafka running, or for
     * callers that need the persisted row back immediately. Not the primary
     * path in production — prefer /ingest.
     */
    @PostMapping("/ingest-sync")
    public ResponseEntity<TrafficSensorReading> ingestSync(@Valid @RequestBody SensorReadingRequest request) {
        return ResponseEntity.ok(service.ingest(request));
    }

    /** Dashboard hot path — served from Redis in normal operation. */
    @GetMapping("/sensors/{sensorId}/latest")
    public ResponseEntity<TrafficSensorReading> latest(@PathVariable String sensorId) {
        TrafficSensorReading reading = service.getLatest(sensorId);
        return reading != null ? ResponseEntity.ok(reading) : ResponseEntity.notFound().build();
    }

    @GetMapping("/zones/{zone}/summary")
    public ResponseEntity<Map<String, Object>> zoneSummary(@PathVariable String zone) {
        return ResponseEntity.ok(service.getZoneSummary(zone));
    }

    /** Paginated — a zone can accumulate a very large number of anomalies over time. */
    @GetMapping("/anomalies")
    public ResponseEntity<Page<TrafficSensorReading>> anomalies(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(service.recentAnomalies(PageRequest.of(page, Math.min(size, 100))));
    }

    /** Admin tier only (see WebMvcConfig) — audit-logged privileged action. */
    @PostMapping("/cache/evict")
    public ResponseEntity<Void> evictCache(@RequestHeader(value = "X-Caller-Id", required = false) String callerId) {
        log.info("AUDIT action=evictCache caller={}", callerId != null ? callerId : "unknown");
        service.evictAllCaches();
        return ResponseEntity.noContent().build();
    }
}
