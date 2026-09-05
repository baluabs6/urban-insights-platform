package com.urban.ai.genai;

import com.urban.ai.client.UrbanDataClient;
import com.urban.ai.dto.GenAiDtos.AnomalyExplanationResponse;
import dev.langchain4j.model.chat.ChatLanguageModel;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * Turns a raw anomaly flag (z-score > threshold) into a plain-language, possibly
 * causally-linked explanation by cross-referencing it with citizen complaints
 * filed in the same zone around the same time — e.g. correlating an AQI spike
 * with nearby "construction dust" or "open burning" complaints.
 */
@Service
@RequiredArgsConstructor
public class AnomalyExplanationService {

    private final UrbanDataClient dataClient;
    private final ChatLanguageModel chatLanguageModel;

    public AnomalyExplanationResponse explain(String zone) {
        List<Map<String, Object>> anomalies = dataClient.getRecentAnomaliesForZone(zone);
        List<Map<String, Object>> complaints = dataClient.getZoneComplaints(zone);

        if (anomalies.isEmpty()) {
            return AnomalyExplanationResponse.builder()
                    .zone(zone)
                    .explanation("No anomalies recorded for this zone in the last 24 hours.")
                    .anomalyCount(0)
                    .relatedComplaintCount(complaints.size())
                    .build();
        }

        String anomalyBlock = anomalies.stream()
                .map(a -> String.format("sensorId=%s, sensorType=%s, value=%s, anomalyScore=%s",
                        a.get("sensorId"), a.get("sensorType"), a.get("value"), a.get("anomalyScore")))
                .reduce((a, b) -> a + "\n- " + b).orElse("");

        String complaintBlock = complaints.stream()
                .map(c -> String.format("category=%s, description=\"%s\"", c.get("category"), c.get("description")))
                .reduce((a, b) -> a + "\n- " + b).orElse("(none)");

        String prompt = """
                Sensor anomalies were detected in zone %s:
                - %s

                Citizen complaints filed in the same zone:
                - %s

                In 2-3 sentences, explain what might be causing these anomalies, referencing any
                plausible link to the complaints above. If there's no plausible link, say the
                anomalies appear unrelated to the reported complaints and suggest what else could
                explain them (e.g. equipment fault, seasonal pattern, traffic surge).
                """.formatted(zone, anomalyBlock, complaintBlock);

        String explanation;
        try {
            explanation = chatLanguageModel.generate(prompt);
        } catch (Exception e) {
            explanation = "AI model unavailable. Raw anomalies:\n- " + anomalyBlock;
        }

        return AnomalyExplanationResponse.builder()
                .zone(zone)
                .explanation(explanation)
                .anomalyCount(anomalies.size())
                .relatedComplaintCount(complaints.size())
                .build();
    }
}
