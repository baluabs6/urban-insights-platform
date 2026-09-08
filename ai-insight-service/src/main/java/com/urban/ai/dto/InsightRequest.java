package com.urban.ai.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class InsightRequest {

    @NotBlank
    @Size(max = 200)
    private String zone;

    @NotBlank
    @Size(max = 1000, message = "question must be 1000 characters or fewer")
    private String question; // e.g. "Why is traffic bad here today and what's being done about it?"
}
