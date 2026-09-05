package com.urban.traffic.service;

import com.urban.traffic.dto.SensorReadingRequest;
import com.urban.traffic.entity.TrafficSensorReading;
import com.urban.traffic.repository.TrafficSensorRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.CachePut;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
@Slf4j
public class TrafficIngestionService {

    private final TrafficSensorRepository repository;
    private final RedisTemplate<String, Object> redisTemplate;

    private static final double ANOMALY_Z_SCORE_THRESHOLD = 3.0;

    /**
     * Real-time ingestion path:
     *  1. Compute rolling mean/stddev for the sensor (last 24h, backed by Postgres,
     *     itself cached briefly in Redis to avoid hammering the DB on every event).
     *  2. Score the incoming value with a z-score -> flag anomaly (lightweight AI module).
     *  3. Persist to Postgres (source of truth).
     *  4. Push "latest reading" into Redis so dashboards get sub-ms reads.
     */
    @CachePut(value = "latestReadings", key = "#request.sensorId")
    public TrafficSensorReading ingest(SensorReadingRequest request) {
        Instant now = Instant.now();
        Instant since = now.minus(24, ChronoUnit.HOURS);

        Double mean = repository.averageValueSince(request.getSensorId(), since);
        Double stdDev = repository.stdDevSince(request.getSensorId(), since);

        boolean anomaly = false;
        Double zScore = null;
        if (mean != null && stdDev != null && stdDev > 0) {
            zScore = Math.abs((request.getValue() - mean) / stdDev);
            anomaly = zScore > ANOMALY_Z_SCORE_THRESHOLD;
        }

        TrafficSensorReading reading = TrafficSensorReading.builder()
                .sensorId(request.getSensorId())
                .sensorType(request.getSensorType())
                .zone(request.getZone())
                .latitude(request.getLatitude())
                .longitude(request.getLongitude())
                .value(request.getValue())
                .unit(request.getUnit())
                .recordedAt(now)
                .anomaly(anomaly)
                .anomalyScore(zScore)
                .build();

        TrafficSensorReading saved = repository.save(reading);

        if (anomaly) {
            log.warn("Anomaly detected on sensor {} in zone {}: value={}, zScore={}",
                    request.getSensorId(), request.getZone(), request.getValue(), zScore);
            // Bump a Redis counter the ops dashboard / alerting service can watch.
            redisTemplate.opsForValue().increment("anomaly:count:" + request.getZone());
            redisTemplate.expire("anomaly:count:" + request.getZone(), 1, TimeUnit.HOURS);
        }

        return saved;
    }

    @Cacheable(value = "latestReadings", key = "#sensorId")
    public TrafficSensorReading getLatest(String sensorId) {
        return repository.findTop50BySensorIdOrderByRecordedAtDesc(sensorId)
                .stream().findFirst().orElse(null);
    }

    @Cacheable(value = "zoneSummary", key = "#zone")
    public Map<String, Object> getZoneSummary(String zone) {
        List<TrafficSensorReading> readings = repository.findByZone(zone);
        double avg = readings.stream().mapToDouble(TrafficSensorReading::getValue).average().orElse(0);
        long anomalies = readings.stream().filter(TrafficSensorReading::getAnomaly).count();
        return Map.of(
                "zone", zone,
                "readingCount", readings.size(),
                "averageValue", avg,
                "anomalyCount", anomalies
        );
    }

    @CacheEvict(value = {"latestReadings", "zoneSummary"}, allEntries = true)
    public void evictAllCaches() {
        log.info("Manually evicted traffic caches");
    }

    public List<TrafficSensorReading> recentAnomalies() {
        return repository.findByAnomalyTrueAndRecordedAtAfter(Instant.now().minus(24, ChronoUnit.HOURS));
    }
}
