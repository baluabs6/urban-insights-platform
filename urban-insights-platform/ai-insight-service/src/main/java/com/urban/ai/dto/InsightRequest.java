package com.urban.ai.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class InsightRequest {

    @NotBlank
    private String zone;

    @NotBlank
    private String question; // e.g. "Why is traffic bad here today and what's being done about it?"
}
