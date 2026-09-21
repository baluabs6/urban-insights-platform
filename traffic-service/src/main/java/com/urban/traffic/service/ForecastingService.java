package com.urban.traffic.service;

import com.urban.traffic.entity.TrafficSensorReading;
import com.urban.traffic.repository.TrafficSensorRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class ForecastingService {

    private final TrafficSensorRepository repository;
    private final RedisTemplate<String, Object> redisTemplate;

    @Value("${forecast.zones:Whitefield,Koramangala,Connaught Place,Andheri}")
    private List<String> zones;

    @Value("${forecast.horizon-minutes:30}")
    private int horizonMinutes;

    @Value("${forecast.lookback-minutes:180}")
    private int lookbackMinutes;

    private static final int MIN_POINTS_PER_SENSOR = 5;
    private static final Duration CACHE_TTL = Duration.ofMinutes(20);

    public record SensorForecast(String sensorId, double currentValue, double projectedValue, String trend) {}

    public record ZoneForecast(String zone, int sensorsConsidered, double avgCurrentValue,
                                double avgProjectedValue, String trend, boolean likelyBreach,
                                String generatedAt) {}

    @Scheduled(cron = "${forecast.refresh-cron:0 */10 * * * *}")
    public void refreshAllZones() {
        for (String zone : zones) {
            try {
                ZoneForecast forecast = computeZoneForecast(zone.trim());
                cacheForecast(forecast);
            } catch (Exception e) {
                log.warn("Forecast refresh failed for zone {}: {}", zone, e.getMessage());
            }
        }
    }

    @SuppressWarnings("unchecked")
    public ZoneForecast getForecast(String zone) {
        Object cached = redisTemplate.opsForValue().get(cacheKey(zone));
        if (cached instanceof Map<?, ?> map) {
            return fromCachedMap((Map<String, Object>) map);
        }
        ZoneForecast fresh = computeZoneForecast(zone);
        cacheForecast(fresh);
        return fresh;
    }

    private ZoneForecast computeZoneForecast(String zone) {
        Instant now = Instant.now();
        Instant from = now.minus(lookbackMinutes, java.time.temporal.ChronoUnit.MINUTES);

        List<TrafficSensorReading> readings =
                repository.findByZoneAndRecordedAtBetweenOrderByRecordedAtAsc(zone, from, now);

        Map<String, List<TrafficSensorReading>> bySensor =
                readings.stream().collect(Collectors.groupingBy(TrafficSensorReading::getSensorId));

        List<SensorForecast> perSensor = new ArrayList<>();
        for (var entry : bySensor.entrySet()) {
            List<TrafficSensorReading> series = entry.getValue();
            if (series.size() < MIN_POINTS_PER_SENSOR) continue;
            SensorForecast sf = projectSensor(entry.getKey(), series, now);
            if (sf != null) perSensor.add(sf);
        }

        if (perSensor.isEmpty()) {
            return new ZoneForecast(zone, 0, 0.0, 0.0, "INSUFFICIENT_DATA", false, now.toString());
        }

        double avgCurrent = perSensor.stream().mapToDouble(SensorForecast::currentValue).average().orElse(0.0);
        double avgProjected = perSensor.stream().mapToDouble(SensorForecast::projectedValue).average().orElse(0.0);

        String trend = avgProjected > avgCurrent * 1.10 ? "RISING"
                : avgProjected < avgCurrent * 0.90 ? "FALLING"
                : "STABLE";

        boolean likelyBreach = avgCurrent > 0 && avgProjected > avgCurrent * 1.5;

        return new ZoneForecast(zone, perSensor.size(),
                round2(avgCurrent), round2(avgProjected), trend, likelyBreach, now.toString());
    }

    private SensorForecast projectSensor(String sensorId, List<TrafficSensorReading> series, Instant now) {
        series.sort(Comparator.comparing(TrafficSensorReading::getRecordedAt));
        Instant t0 = series.get(0).getRecordedAt();

        int n = series.size();
        double sumX = 0, sumY = 0, sumXY = 0, sumXX = 0;
        for (TrafficSensorReading r : series) {
            double x = Duration.between(t0, r.getRecordedAt()).toSeconds();
            double y = r.getValue();
            sumX += x; sumY += y; sumXY += x * y; sumXX += x * x;
        }
        double denom = (n * sumXX - sumX * sumX);
        if (denom == 0) return null;

        double slope = (n * sumXY - sumX * sumY) / denom;
        double intercept = (sumY - slope * sumX) / n;

        double currentX = Duration.between(t0, now).toSeconds();
        double projectedX = currentX + Duration.ofMinutes(horizonMinutes).toSeconds();

        double currentValue = series.get(n - 1).getValue();
        double projectedValue = Math.max(0.0, intercept + slope * projectedX);

        String trend = projectedValue > currentValue * 1.10 ? "RISING"
                : projectedValue < currentValue * 0.90 ? "FALLING"
                : "STABLE";

        return new SensorForecast(sensorId, round2(currentValue), round2(projectedValue), trend);
    }

    private void cacheForecast(ZoneForecast forecast) {
        Map<String, Object> asMap = Map.of(
                "zone", forecast.zone(),
                "sensorsConsidered", forecast.sensorsConsidered(),
                "avgCurrentValue", forecast.avgCurrentValue(),
                "avgProjectedValue", forecast.avgProjectedValue(),
                "trend", forecast.trend(),
                "likelyBreach", forecast.likelyBreach(),
                "generatedAt", forecast.generatedAt()
        );
        redisTemplate.opsForValue().set(cacheKey(forecast.zone()), asMap, CACHE_TTL.toMinutes(), TimeUnit.MINUTES);
    }

    private ZoneForecast fromCachedMap(Map<String, Object> map) {
        return new ZoneForecast(
                String.valueOf(map.get("zone")),
                ((Number) map.getOrDefault("sensorsConsidered", 0)).intValue(),
                ((Number) map.getOrDefault("avgCurrentValue", 0.0)).doubleValue(),
                ((Number) map.getOrDefault("avgProjectedValue", 0.0)).doubleValue(),
                String.valueOf(map.get("trend")),
                Boolean.TRUE.equals(map.get("likelyBreach")),
                String.valueOf(map.get("generatedAt"))
        );
    }

    private String cacheKey(String zone) {
        return "forecast:zone:" + zone;
    }

    private double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
