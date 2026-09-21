package com.urban.ai.genai;

import com.urban.ai.client.UrbanDataClient;
import com.urban.ai.dto.GenAiDtos.CityBriefingResponse;
import dev.langchain4j.model.chat.ChatLanguageModel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class CityBriefingService {

    private final UrbanDataClient dataClient;
    private final ChatLanguageModel chatLanguageModel;
    private final com.urban.ai.metrics.LlmCallMetrics llmCallMetrics;
    private final org.springframework.data.redis.core.RedisTemplate<String, Object> redisTemplate;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper = new com.fasterxml.jackson.databind.ObjectMapper();

    private static final String BRIEFING_CACHE_KEY = "city-briefing:latest";

    @Value("${city.zones:Whitefield,Koramangala,Connaught Place,Andheri}")
    private List<String> zones;

    @Scheduled(cron = "${city.briefing-cron:0 0 7 * * *}")
    public void generateScheduledBriefing() {
        log.info("Generating scheduled city briefing for zones: {}", zones);
        CityBriefingResponse briefing = generate();
        persist(briefing);
    }

    public CityBriefingResponse getLatest() {
        try {
            Object raw = redisTemplate.opsForValue().get(BRIEFING_CACHE_KEY);
            if (raw != null) {
                String json = raw instanceof String s ? s : objectMapper.writeValueAsString(raw);
                return objectMapper.readValue(json, CityBriefingResponse.class);
            }
        } catch (Exception e) {
            log.warn("Failed to read cached briefing from Redis, regenerating: {}", e.getMessage());
        }
        CityBriefingResponse fresh = generate();
        persist(fresh);
        return fresh;
    }

    private void persist(CityBriefingResponse briefing) {
        try {
            redisTemplate.opsForValue().set(BRIEFING_CACHE_KEY, objectMapper.writeValueAsString(briefing),
                    java.time.Duration.ofHours(25));
        } catch (Exception e) {
            log.warn("Failed to persist briefing to Redis: {}", e.getMessage());
        }
    }

    public CityBriefingResponse refreshAndPersist() {
        CityBriefingResponse briefing = generate();
        persist(briefing);
        return briefing;
    }

    public CityBriefingResponse generate() {
        List<Map<String, Object>> anomalies = dataClient.getRecentAnomalies();
        List<Map<String, Object>> urgentComplaints = dataClient.getTopUrgentComplaints();

        StringBuilder zoneSummaries = new StringBuilder();
        for (String zone : zones) {
            Map<String, Object> summary = dataClient.getZoneTrafficSummary(zone);
            zoneSummaries.append(String.format("- %s: avgValue=%s, anomalyCount=%s%n",
                    zone, summary.getOrDefault("averageValue", "n/a"), summary.getOrDefault("anomalyCount", "n/a")));
        }

        String anomalyBlock = anomalies.isEmpty() ? "(none)" : anomalies.stream()
                .map(a -> String.format("%s in %s (score=%s)", a.get("sensorType"), a.get("zone"), a.get("anomalyScore")))
                .reduce((a, b) -> a + "; " + b).orElse("(none)");

        String complaintBlock = urgentComplaints.isEmpty() ? "(none)" : urgentComplaints.stream()
                .limit(10)
                .map(c -> String.format("[%s] %s (urgency=%s)", c.get("zone"), c.get("category"), c.get("urgencyScore")))
                .reduce((a, b) -> a + "; " + b).orElse("(none)");

        String prompt = """
                Write a concise daily city-operations briefing (4-6 sentences, plain language,
                for a municipal control room) based on this data:

                Zone summaries:
                %s

                Sensor anomalies (last 24h): %s

                Top urgent citizen complaints: %s

                Structure it as: 1) overall status, 2) zones needing attention, 3) recommended
                next actions for city crews.
                """.formatted(zoneSummaries, anomalyBlock, complaintBlock);

        String briefingText;
        try {
            briefingText = llmCallMetrics.time("city_briefing", () -> chatLanguageModel.generate(prompt));
        } catch (Exception e) {
            log.warn("LLM unavailable for briefing, returning raw data summary: {}", e.getMessage());
            briefingText = "AI model unavailable. Raw summary:\nZones:\n" + zoneSummaries
                    + "\nAnomalies: " + anomalyBlock + "\nUrgent complaints: " + complaintBlock;
        }

        return CityBriefingResponse.builder()
                .generatedAt(Instant.now().toString())
                .zonesCovered(zones)
                .briefing(briefingText)
                .build();
    }
}
