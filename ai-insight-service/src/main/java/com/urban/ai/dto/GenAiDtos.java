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

    // ---------------------------------------------------------------------
    // Hotspot prediction (complaint-volume forecasting AI module)
    // ---------------------------------------------------------------------

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ZoneHotspotScore {
        private String zone;
        private double riskScore;      // 0.0-1.0, higher = more likely to spike
        private String riskLevel;      // LOW / MEDIUM / HIGH
        private int recentComplaintCount;
        private int recentAnomalyCount;
        private String trend;          // from ForecastingService: RISING/STABLE/FALLING/unknown
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class HotspotPredictionResponse {
        private String generatedAt;
        private List<ZoneHotspotScore> zones;
    }

    // ---------------------------------------------------------------------
    // Photo verification (vision AI module)
    // ---------------------------------------------------------------------

    @Data
    public static class PhotoVerificationRequest {
        @NotBlank
        private String complaintId;
        @NotBlank
        private String category;
        @NotNull
        @Size(min = 1, max = 5, message = "provide between 1 and 5 photo URLs")
        private List<@NotBlank String> photoUrls;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PhotoVerificationResponse {
        private String complaintId;
        private boolean verified;
        private double confidence; // 0.0-1.0
        private String note;
    }

    // ---------------------------------------------------------------------
    // Sentiment / frustration scoring (separate signal from category urgency)
    // ---------------------------------------------------------------------

    @Data
    public static class SentimentRequest {
        @NotBlank
        @Size(max = 2000)
        private String description;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SentimentResponse {
        private double sentimentUrgencyScore; // 0.0-1.0 — tone/frustration/safety-language signal
        private boolean repeatComplainantLanguage;
        private boolean safetyCriticalLanguage;
        private String summary;
    }

    // ---------------------------------------------------------------------
    // Root-cause / multi-hop causal-chain inference
    // ---------------------------------------------------------------------

    @Data
    public static class RootCauseRequest {
        @NotNull
        @Size(min = 1, max = 10, message = "provide between 1 and 10 zones")
        private List<@Size(max = 200) String> zones;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RootCauseResponse {
        private List<String> zonesConsidered;
        private String causalChain; // the LLM's multi-hop reasoning, grounded in retrieved evidence
        private int evidenceItemCount;
    }

    // ---------------------------------------------------------------------
    // Classification feedback loop (ops corrections -> few-shot exemplars)
    // ---------------------------------------------------------------------

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ClassificationFeedbackStats {
        private int exemplarCount;
        private List<String> recentCorrections; // short human-readable summaries, most recent first
    }

    // ---------------------------------------------------------------------
    // Voice complaint intake (transcription -> translate -> classify)
    // ---------------------------------------------------------------------

    @Data
    public static class VoiceComplaintRequest {
        @NotBlank
        private String audioBase64;
        /** e.g. "wav", "mp3", "ogg" — passed through to the transcription backend. */
        @NotBlank
        private String audioFormat;
        @Size(max = 200)
        private String zone;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class VoiceComplaintResponse {
        private String transcript;
        private String detectedLanguage;
        private ClassifyResponse classification;
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
