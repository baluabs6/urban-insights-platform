package com.urban.complaint.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.util.List;

/** Payload ai-insight-service PATCHes back once async classification/duplicate-check completes. */
@Data
public class ClassificationUpdateRequest {

    @NotBlank
    private String category;

    @NotBlank
    private String department;

    private Double urgencyScore;
    private List<String> tags;

    @NotBlank
    private String classificationSource; // "AI" or "HEURISTIC_FALLBACK"

    private Boolean likelyDuplicate;
    private Double duplicateSimilarityScore;
    private List<String> similarComplaintDescriptions;

    private Boolean photoVerified;
    private String photoVerificationNote;

    private Double sentimentUrgencyScore;
    private String sentimentSummary;
}
