package com.urban.ai.tools;

import com.urban.ai.client.UrbanDataClient;
import com.urban.ai.security.PromptSafetyUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Live city data exposed as Spring AI tools.
 *
 * <p>The same methods back two features: (1) the agentic {@code POST /api/insights/agent} endpoint, where the
 * model decides which data it needs instead of us pre-fetching context, and (2) the optional MCP server, where
 * external MCP clients (Claude Desktop, IDE agents) can call them directly.
 *
 * <p>All tools are read-only and go through {@link UrbanDataClient}, so they inherit its API key, retries and
 * circuit breakers. Citizen-written complaint text is size-limited and wrapped as untrusted data before it
 * is handed back to a model.
 */
@Component
@RequiredArgsConstructor
public class UrbanDataTools {

    private static final int MAX_ITEMS = 15;
    private static final int MAX_DESCRIPTION_CHARS = 200;

    private final UrbanDataClient dataClient;
    private final PromptSafetyUtils promptSafetyUtils;

    @Tool(description = "Get the sensor summary for one city zone: average sensor reading, number of readings "
            + "and number of anomalies flagged in the last 24 hours.")
    public Map<String, Object> getZoneTrafficSummary(
            @ToolParam(description = "Zone name, e.g. Whitefield, Koramangala, Connaught Place, Andheri") String zone) {
        return dataClient.getZoneTrafficSummary(zone);
    }

    @Tool(description = "List recent sensor anomalies (statistical outliers) for one city zone.")
    public List<Map<String, Object>> getRecentAnomalies(
            @ToolParam(description = "Zone name, e.g. Whitefield") String zone) {
        return dataClient.getRecentAnomaliesForZone(zone).stream().limit(MAX_ITEMS).toList();
    }

    @Tool(description = "List recent citizen complaints filed in one city zone (category, status, urgency, description).")
    public List<Map<String, Object>> getZoneComplaints(
            @ToolParam(description = "Zone name, e.g. Whitefield") String zone) {
        return dataClient.getZoneComplaints(zone).stream().limit(MAX_ITEMS).map(this::sanitize).toList();
    }

    @Tool(description = "List the most urgent open citizen complaints across the whole city.")
    public List<Map<String, Object>> getUrgentComplaints() {
        return dataClient.getTopUrgentComplaints().stream().limit(MAX_ITEMS).map(this::sanitize).toList();
    }

    @Tool(description = "Get the traffic/sensor forecast for one city zone.")
    public Map<String, Object> getZoneForecast(
            @ToolParam(description = "Zone name, e.g. Whitefield") String zone) {
        return dataClient.getZoneForecast(zone);
    }

    private Map<String, Object> sanitize(Map<String, Object> complaint) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (String key : List.of("id", "zone", "category", "status", "urgencyScore", "assignedDepartment", "createdAt")) {
            if (complaint.containsKey(key)) out.put(key, complaint.get(key));
        }
        String description = String.valueOf(complaint.getOrDefault("description", ""));
        if (description.length() > MAX_DESCRIPTION_CHARS) {
            description = description.substring(0, MAX_DESCRIPTION_CHARS) + "...";
        }
        out.put("description", promptSafetyUtils.wrapUntrusted(description));
        return out;
    }
}
