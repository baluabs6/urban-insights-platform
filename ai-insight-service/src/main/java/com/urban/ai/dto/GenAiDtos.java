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
        private Double urgencyScore;
        private List<String> tags;
        private String reasoning;
        private String detectedLanguage;
        private String source;
    }

    @Data
    public static class DuplicateCheckRequest {
        @NotBlank
        @Size(max = 2000)
        private String description;
        @NotBlank
        @Size(max = 200)
        private String zone;
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
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ZoneHotspotScore {
        private String zone;
        private double riskScore;
        private String riskLevel;
        private int recentComplaintCount;
        private int recentAnomalyCount;
        private String trend;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class HotspotPredictionResponse {
        private String generatedAt;
        private List<ZoneHotspotScore> zones;
    }

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
        private double confidence;
        private String note;
    }

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
        private double sentimentUrgencyScore;
        private boolean repeatComplainantLanguage;
        private boolean safetyCriticalLanguage;
        private String summary;
    }

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
        private String causalChain;
        private int evidenceItemCount;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ClassificationFeedbackStats {
        private int exemplarCount;
        private List<String> recentCorrections;
    }

    @Data
    public static class VoiceComplaintRequest {
        @NotBlank
        private String audioBase64;
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
        private String citizenId;
    }
}
