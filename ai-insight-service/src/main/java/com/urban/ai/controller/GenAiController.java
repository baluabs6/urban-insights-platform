package com.urban.ai.controller;

import com.urban.ai.dto.GenAiDtos.*;
import com.urban.ai.genai.AnomalyExplanationService;
import com.urban.ai.genai.CityBriefingService;
import com.urban.ai.genai.ComplaintClassificationService;
import com.urban.ai.genai.ComplaintStatusChatService;
import com.urban.ai.genai.DuplicateDetectionService;
import com.urban.ai.genai.ZoneComparisonService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * New GenAI-powered endpoints:
 *  - /classify-complaint : LLM-based category/urgency/department classification (multi-language aware)
 *  - /duplicate-check    : embedding-similarity duplicate complaint detection
 *  - /compare-zones      : multi-zone comparative Q&A
 *  - /anomaly-explanation/{zone} : root-cause explanation correlating anomalies + complaints
 *  - /city-briefing      : on-demand version of the scheduled daily briefing
 *  - /complaint-status   : citizen-facing "where's my complaint?" chatbot (WhatsApp/SMS-ready)
 *
 * Admin-tier gating and rate limiting for these paths are both registered
 * centrally in WebMvcConfig (interceptors), not per-method here — keeps the
 * cross-cutting policy in one place instead of scattered annotations that
 * are easy to forget on a new endpoint.
 */
@RestController
@RequestMapping("/api/ai")
@RequiredArgsConstructor
public class GenAiController {

    private final ComplaintClassificationService classificationService;
    private final DuplicateDetectionService duplicateDetectionService;
    private final ZoneComparisonService zoneComparisonService;
    private final AnomalyExplanationService anomalyExplanationService;
    private final CityBriefingService cityBriefingService;
    private final ComplaintStatusChatService complaintStatusChatService;
    private final com.urban.ai.genai.SlaEscalationService slaEscalationService;

    @PostMapping("/classify-complaint")
    public ResponseEntity<ClassifyResponse> classify(@Valid @RequestBody ClassifyRequest request) {
        return ResponseEntity.ok(classificationService.classify(request));
    }

    @PostMapping("/duplicate-check")
    public ResponseEntity<DuplicateCheckResponse> duplicateCheck(@Valid @RequestBody DuplicateCheckRequest request) {
        return ResponseEntity.ok(duplicateDetectionService.check(request));
    }

    @PostMapping("/compare-zones")
    public ResponseEntity<CompareZonesResponse> compareZones(@Valid @RequestBody CompareZonesRequest request) {
        return ResponseEntity.ok(zoneComparisonService.compare(request));
    }

    @GetMapping("/anomaly-explanation/{zone}")
    public ResponseEntity<AnomalyExplanationResponse> explainAnomaly(@PathVariable String zone) {
        return ResponseEntity.ok(anomalyExplanationService.explain(zone));
    }

    // Not rate-limited: reads a cached/persisted value (Redis) rather than calling
    // the LLM on every request — refresh=true is the expensive path and is itself
    // gated to the scheduled job + explicit opt-in, an acceptable lower-frequency risk.
    @GetMapping("/city-briefing")
    public ResponseEntity<CityBriefingResponse> cityBriefing(@RequestParam(defaultValue = "false") boolean refresh) {
        return ResponseEntity.ok(refresh ? cityBriefingService.refreshAndPersist() : cityBriefingService.getLatest());
    }

    @PostMapping("/complaint-status")
    public ResponseEntity<ComplaintStatusChatService.StatusChatResponse> complaintStatus(
            @Valid @RequestBody StatusChatRequest request) {
        return ResponseEntity.ok(complaintStatusChatService.ask(
                request.getComplaintId(), request.getQuestion(), request.getCitizenId()));
    }

    @GetMapping("/sla-escalations")
    public ResponseEntity<java.util.List<com.urban.ai.genai.SlaEscalationService.EscalationItem>> slaEscalations() {
        return ResponseEntity.ok(slaEscalationService.checkBreaches());
    }
}
