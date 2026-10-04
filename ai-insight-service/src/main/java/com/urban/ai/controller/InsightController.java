package com.urban.ai.controller;

import com.urban.ai.dto.AgentRequest;
import com.urban.ai.dto.AgentResponse;
import com.urban.ai.dto.InsightRequest;
import com.urban.ai.dto.InsightResponse;
import com.urban.ai.service.AgenticInsightService;
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
    private final AgenticInsightService agenticInsightService;

    @PostMapping("/ask")
    public ResponseEntity<InsightResponse> ask(@Valid @RequestBody InsightRequest request) {
        return ResponseEntity.ok(ragInsightService.answer(request));
    }

    /** Tool-calling + multi-turn variant: the model decides which live data to fetch. */
    @PostMapping("/agent")
    public ResponseEntity<AgentResponse> agent(@Valid @RequestBody AgentRequest request) {
        return ResponseEntity.ok(agenticInsightService.ask(request));
    }
}
