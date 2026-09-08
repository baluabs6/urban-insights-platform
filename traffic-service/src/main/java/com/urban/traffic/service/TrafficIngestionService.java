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

import java.time.Duration;
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

    // Rolling-window anomaly baseline, kept entirely in Redis — see javadoc on ingest().
    private static final int ROLLING_WINDOW_SIZE = 100;
    private static final int MIN_SAMPLES_FOR_SCORING = 10;
    private static final Duration ROLLING_WINDOW_TTL = Duration.ofHours(48); // stale/dead sensors self-clean

    /**
     * Real-time ingestion path:
     *  1. Score the incoming value against a per-sensor rolling baseline —
     *     entirely in Redis (see below), NOT a Postgres aggregate query.
     *  2. Persist to Postgres (source of truth for the actual reading).
     *  3. Push "latest reading" into Redis so dashboards get sub-ms reads.
     *
     * Scalability fix: this used to run TWO Postgres aggregate queries
     * (AVG/STDDEV over a 24h window) on every single ingested reading — at
     * real sensor-fleet throughput that's a DB round-trip per event, and it
     * gets slower as the 24h window fills with more rows. The baseline is now
     * a bounded Redis LIST per sensor (last ROLLING_WINDOW_SIZE readings,
     * O(1) push+trim, mean/stddev computed over at most 100 values in-process)
     * — Postgres is no longer touched at all for anomaly scoring, only for
     * the actual persistence write below.
     *
     * Trade-off: this is now "baseline = last N readings" rather than
     * "baseline = all readings in the last 24h" — for a sensor reporting
     * every few minutes those are similar in practice, but it's a genuine
     * semantic change worth knowing about if reporting frequency varies wildly
     * across sensors.
     */
    @CachePut(value = "latestReadings", key = "#request.sensorId")
    public TrafficSensorReading ingest(SensorReadingRequest request) {
        Instant now = Instant.now();

        AnomalyScore score = scoreAgainstRollingWindow(request.getSensorId(), request.getValue());

        TrafficSensorReading reading = TrafficSensorReading.builder()
                .sensorId(request.getSensorId())
                .sensorType(request.getSensorType())
                .zone(request.getZone())
                .latitude(request.getLatitude())
                .longitude(request.getLongitude())
                .value(request.getValue())
                .unit(request.getUnit())
                .recordedAt(now)
                .anomaly(score.anomaly())
                .anomalyScore(score.zScore())
                .build();

        TrafficSensorReading saved = repository.save(reading);

        if (score.anomaly()) {
            log.warn("Anomaly detected on sensor {} in zone {}: value={}, zScore={}",
                    request.getSensorId(), request.getZone(), request.getValue(), score.zScore());
            // Bump a Redis counter the ops dashboard / alerting service can watch.
            redisTemplate.opsForValue().increment("anomaly:count:" + request.getZone());
            redisTemplate.expire("anomaly:count:" + request.getZone(), 1, TimeUnit.HOURS);
        }

        pushToRollingWindow(request.getSensorId(), request.getValue());

        return saved;
    }

    private record AnomalyScore(boolean anomaly, Double zScore) {}

    /**
     * Reads the sensor's current rolling window from Redis, scores the new
     * value against it. Deliberately reads BEFORE pushing the new value (see
     * pushToRollingWindow) so a single spike doesn't immediately pollute its
     * own baseline before being scored against it.
     */
    @SuppressWarnings("unchecked")
    private AnomalyScore scoreAgainstRollingWindow(String sensorId, double newValue) {
        String key = rollingWindowKey(sensorId);
        List<Object> raw = redisTemplate.opsForList().range(key, 0, -1);

        if (raw == null || raw.size() < MIN_SAMPLES_FOR_SCORING) {
            return new AnomalyScore(false, null); // not enough history to establish a baseline yet
        }

        double[] values = raw.stream().mapToDouble(v -> Double.parseDouble(String.valueOf(v))).toArray();
        double mean = java.util.stream.DoubleStream.of(values).average().orElse(0.0);
        double variance = java.util.stream.DoubleStream.of(values)
                .map(v -> (v - mean) * (v - mean))
                .average().orElse(0.0);
        double stdDev = Math.sqrt(variance);

        if (stdDev <= 0) {
            return new AnomalyScore(false, null); // no variation in the baseline yet — can't compute a meaningful z-score
        }

        double zScore = Math.abs((newValue - mean) / stdDev);
        return new AnomalyScore(zScore > ANOMALY_Z_SCORE_THRESHOLD, zScore);
    }

    private void pushToRollingWindow(String sensorId, double value) {
        String key = rollingWindowKey(sensorId);
        redisTemplate.opsForList().leftPush(key, String.valueOf(value));
        redisTemplate.opsForList().trim(key, 0, ROLLING_WINDOW_SIZE - 1);
        redisTemplate.expire(key, ROLLING_WINDOW_TTL);
    }

    private String rollingWindowKey(String sensorId) {
        return "sensor:rolling-window:" + sensorId;
    }

    @Cacheable(value = "latestReadings", key = "#sensorId")
    public TrafficSensorReading getLatest(String sensorId) {
        return repository.findTop50BySensorIdOrderByRecordedAtDesc(sensorId)
                .stream().findFirst().orElse(null);
    }

    @Cacheable(value = "zoneSummary", key = "#zone")
    public Map<String, Object> getZoneSummary(String zone) {
        long readingCount = repository.countByZone(zone);
        Double avg = repository.averageValueByZone(zone);
        long anomalies = repository.countAnomaliesByZone(zone);
        return Map.of(
                "zone", zone,
                "readingCount", readingCount,
                "averageValue", avg != null ? avg : 0.0,
                "anomalyCount", anomalies
        );
    }

    @CacheEvict(value = {"latestReadings", "zoneSummary"}, allEntries = true)
    public void evictAllCaches() {
        log.info("Manually evicted traffic caches");
    }

    public org.springframework.data.domain.Page<TrafficSensorReading> recentAnomalies(
            org.springframework.data.domain.Pageable pageable) {
        return repository.findByAnomalyTrueAndRecordedAtAfter(Instant.now().minus(24, ChronoUnit.HOURS), pageable);
    }
}
