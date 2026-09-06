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
    private List<String> retrievedContext; // shown for transparency / debugging RAG grounding
    private List<Map<String, String>> sources; // per-segment id/type/scores backing the answer (citations)
    private Double faithfulnessScore; // 0.0-1.0, from the second-pass groundedness check
    private String faithfulnessRationale;
}
