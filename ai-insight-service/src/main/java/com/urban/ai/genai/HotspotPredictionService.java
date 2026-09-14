package com.urban.ai.genai;

import com.urban.ai.client.UrbanDataClient;
import com.urban.ai.dto.GenAiDtos.HotspotPredictionResponse;
import com.urban.ai.dto.GenAiDtos.ZoneHotspotScore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Complaint-hotspot prediction AI module: scores each zone's likelihood of a
 * complaint-volume spike in the next 24-48h, rather than only ever reacting
 * to complaints/anomalies after they're filed (ZoneComparisonService,
 * AnomalyExplanationService).
 *
 * Deliberately a transparent weighted-signal model, not a trained classifier —
 * there's no historical labeled "did a spike happen" dataset in this reference
 * project to train one against. It combines three signals this platform
 * already has:
 *   1. recent complaint volume in the zone (complaint-service)
 *   2. recent sensor anomaly count in the zone (traffic-service)
 *   3. the zone's short-horizon forecast trend (ForecastingService, via traffic-service)
 * into a single 0-1 risk score. Swap in a real trained model (e.g. gradient-
 * boosted trees on historical spike labels) once that data exists — the
 * scoring method below is the seam to replace.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class HotspotPredictionService {

    private final UrbanDataClient dataClient;
    private final RedisTemplate<String, Object> redisTemplate;

    @Value("${city.zones:Whitefield,Koramangala,Connaught Place,Andheri}")
    private List<String> zones;

    private static final String CACHE_KEY = "hotspot-prediction:latest";
    private static final int HIGH_COMPLAINT_COUNT = 15;
    private static final int HIGH_ANOMALY_COUNT = 5;

    @Scheduled(cron = "${hotspot.refresh-cron:0 30 * * * *}") // hourly, offset from other jobs
    public void refresh() {
        try {
            HotspotPredictionResponse response = computeAll();
            redisTemplate.opsForValue().set(CACHE_KEY, response, 90, TimeUnit.MINUTES);
        } catch (Exception e) {
            log.warn("Hotspot prediction refresh failed: {}", e.getMessage());
        }
    }

    public HotspotPredictionResponse getLatest() {
        Object cached = redisTemplate.opsForValue().get(CACHE_KEY);
        if (cached instanceof HotspotPredictionResponse cachedResponse) {
            return cachedResponse;
        }
        HotspotPredictionResponse fresh = computeAll();
        redisTemplate.opsForValue().set(CACHE_KEY, fresh, 90, TimeUnit.MINUTES);
        return fresh;
    }

    private HotspotPredictionResponse computeAll() {
        List<ZoneHotspotScore> scores = new ArrayList<>();
        for (String zone : zones) {
            try {
                scores.add(scoreZone(zone.trim()));
            } catch (Exception e) {
                log.warn("Hotspot scoring failed for zone {}: {}", zone, e.getMessage());
            }
        }
        // Highest risk first — this is the list ops actually wants to scan top-down.
        scores.sort((a, b) -> Double.compare(b.getRiskScore(), a.getRiskScore()));
        return HotspotPredictionResponse.builder()
                .generatedAt(Instant.now().toString())
                .zones(scores)
                .build();
    }

    private ZoneHotspotScore scoreZone(String zone) {
        List<Map<String, Object>> complaints = dataClient.getZoneComplaints(zone);
        List<Map<String, Object>> anomalies = dataClient.getRecentAnomaliesForZone(zone);
        Map<String, Object> forecast = dataClient.getZoneForecast(zone);

        int complaintCount = complaints.size();
        int anomalyCount = anomalies.size();
        String trend = forecast != null ? String.valueOf(forecast.getOrDefault("trend", "UNKNOWN")) : "UNKNOWN";
        boolean likelyBreach = forecast != null && Boolean.TRUE.equals(forecast.get("likelyBreach"));

        // Weighted, capped 0-1 signal blend:
        //   complaint volume  — 50% weight (the thing we're actually predicting more of)
        //   anomaly volume    — 30% weight (environmental conditions that tend to generate complaints)
        //   forecast trend    — 20% weight (forward-looking: is it getting worse right now)
        double complaintSignal = Math.min(1.0, complaintCount / (double) HIGH_COMPLAINT_COUNT);
        double anomalySignal = Math.min(1.0, anomalyCount / (double) HIGH_ANOMALY_COUNT);
        double trendSignal = likelyBreach ? 1.0 : "RISING".equals(trend) ? 0.6 : 0.0;

        double riskScore = round2(0.5 * complaintSignal + 0.3 * anomalySignal + 0.2 * trendSignal);
        String riskLevel = riskScore >= 0.66 ? "HIGH" : riskScore >= 0.33 ? "MEDIUM" : "LOW";

        return ZoneHotspotScore.builder()
                .zone(zone)
                .riskScore(riskScore)
                .riskLevel(riskLevel)
                .recentComplaintCount(complaintCount)
                .recentAnomalyCount(anomalyCount)
                .trend(trend)
                .build();
    }

    private double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
