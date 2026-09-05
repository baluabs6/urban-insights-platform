package com.urban.ai.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InsightResponse {
    private String zone;
    private String question;
    private String answer;
    private List<String> retrievedContext; // shown for transparency / debugging RAG grounding
}
