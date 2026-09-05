package com.urban.traffic.controller;

import com.urban.traffic.dto.SensorReadingRequest;
import com.urban.traffic.entity.TrafficSensorReading;
import com.urban.traffic.service.TrafficIngestionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/traffic")
@RequiredArgsConstructor
public class TrafficController {

    private final TrafficIngestionService service;

    /** Called by IoT gateway / Kafka consumer / edge devices for each new reading. */
    @PostMapping("/ingest")
    public ResponseEntity<TrafficSensorReading> ingest(@Valid @RequestBody SensorReadingRequest request) {
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

    @GetMapping("/anomalies")
    public ResponseEntity<List<TrafficSensorReading>> anomalies() {
        return ResponseEntity.ok(service.recentAnomalies());
    }

    @PostMapping("/cache/evict")
    public ResponseEntity<Void> evictCache() {
        service.evictAllCaches();
        return ResponseEntity.noContent().build();
    }
}
