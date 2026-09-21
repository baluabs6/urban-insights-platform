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

    @Scheduled(cron = "${hotspot.refresh-cron:0 30 * * * *}")
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
