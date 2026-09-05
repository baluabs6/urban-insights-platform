package com.urban.ai.controller;

import com.urban.ai.dto.InsightRequest;
import com.urban.ai.dto.InsightResponse;
import com.urban.ai.service.RagInsightService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/insights")
@RequiredArgsConstructor
public class InsightController {

    private final RagInsightService ragInsightService;

    /**
     * Example:
     * POST /api/insights/ask
     * { "zone": "Whitefield", "question": "Why is air quality bad here right now?" }
     */
    @PostMapping("/ask")
    public ResponseEntity<InsightResponse> ask(@Valid @RequestBody InsightRequest request) {
        return ResponseEntity.ok(ragInsightService.answer(request));
    }
}
