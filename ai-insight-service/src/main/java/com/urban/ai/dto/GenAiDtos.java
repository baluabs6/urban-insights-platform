package com.urban.ai.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

public class GenAiDtos {

    @Data
    public static class ClassifyRequest {
        @NotBlank
        @Size(max = 2000)
        private String description;
        @Size(max = 200)
        private String zone;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ClassifyResponse {
        private String category;
        private String department;
        private Double urgencyScore; // 0.0 - 1.0
        private List<String> tags;
        private String reasoning;
        private String detectedLanguage; // e.g. "Hindi", "English"
        private String source; // "AI" or "HEURISTIC_FALLBACK"
    }

    @Data
    public static class DuplicateCheckRequest {
        @NotBlank
        @Size(max = 2000)
        private String description;
        @NotBlank
        @Size(max = 200)
        private String zone;
        /** Optional — if the complaint being checked is already indexed (post-write), exclude its own id from matches. */
        private String excludeComplaintId;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DuplicateCheckResponse {
        private boolean duplicate;
        private double maxSimilarityScore;
        private List<String> similarComplaints;
    }

    @Data
    public static class CompareZonesRequest {
        @NotNull
        @Size(min = 1, max = 20, message = "provide between 1 and 20 zones")
        private List<@Size(max = 200) String> zones;
        @NotBlank
        @Size(max = 1000)
        private String question;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CompareZonesResponse {
        private List<String> zones;
        private String question;
        private String answer;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AnomalyExplanationResponse {
        private String zone;
        private String explanation;
        private int anomalyCount;
        private int relatedComplaintCount;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CityBriefingResponse {
        private String generatedAt;
        private List<String> zonesCovered;
        private String briefing;
    }

    @Data
    public static class StatusChatRequest {
        @NotBlank
        @Size(max = 100)
        private String complaintId;
        @NotBlank
        @Size(max = 1000)
        private String question;
        /**
         * Ownership check: the citizenId that filed the complaint, supplied by the
         * caller. ComplaintStatusChatService verifies this matches the complaint's
         * actual citizenId before answering — closes the "guess an ID, read anyone's
         * complaint" gap. Optional only for backwards-compat with admin/internal
         * callers using the ADMIN API key tier (see ApiKeyAuthFilter).
         */
        private String citizenId;
    }
}
