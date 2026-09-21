package com.urban.complaint.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class ClassificationOverrideRequest {

    @NotBlank
    private String category;

    @NotBlank
    private String department;

    private Double urgencyScore;

    private String correctionNote;
}
