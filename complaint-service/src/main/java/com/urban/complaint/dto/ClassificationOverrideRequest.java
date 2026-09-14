package com.urban.complaint.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * Payload for an ops reviewer correcting an AI classification. Distinct from
 * ClassificationUpdateRequest (which is the AI's own PATCH callback) so the two
 * write paths can never be confused with each other — this one always sets
 * classificationSource=HUMAN_OVERRIDE and is the only path that publishes the
 * "complaint.classification.overridden" feedback event ai-insight-service's
 * ClassificationFeedbackService learns from.
 */
@Data
public class ClassificationOverrideRequest {

    @NotBlank
    private String category;

    @NotBlank
    private String department;

    private Double urgencyScore;

    /** Optional short note on why the AI got it wrong — becomes a few-shot exemplar. */
    private String correctionNote;
}
