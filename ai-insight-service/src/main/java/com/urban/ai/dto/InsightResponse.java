package com.urban.ai.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InsightResponse {
    private String zone;
    private String question;
    private String answer;
    private List<String> retrievedContext;
    private List<Map<String, String>> sources;
    private Double faithfulnessScore;
    private String faithfulnessRationale;
}
