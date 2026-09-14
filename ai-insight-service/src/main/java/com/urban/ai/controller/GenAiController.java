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
    private final com.urban.ai.genai.HotspotPredictionService hotspotPredictionService;
    private final com.urban.ai.genai.PhotoVerificationService photoVerificationService;
    private final com.urban.ai.genai.SentimentUrgencyService sentimentUrgencyService;
    private final com.urban.ai.genai.RootCauseChainService rootCauseChainService;
    private final com.urban.ai.genai.ClassificationFeedbackService classificationFeedbackService;
    private final com.urban.ai.genai.VoiceTranscriptionService voiceTranscriptionService;

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

    /** Complaint-volume hotspot risk per zone (see HotspotPredictionService). Served from
     *  an hourly-refreshed cache — cheap enough for a dashboard to poll directly. */
    @GetMapping("/hotspots")
    public ResponseEntity<HotspotPredictionResponse> hotspots() {
        return ResponseEntity.ok(hotspotPredictionService.getLatest());
    }

    /** On-demand vision check that a complaint's photo(s) match its category. Normally
     *  runs automatically in ComplaintCreatedListener; this is for manual re-checks. */
    @PostMapping("/verify-photo")
    public ResponseEntity<PhotoVerificationResponse> verifyPhoto(@Valid @RequestBody PhotoVerificationRequest request) {
        return ResponseEntity.ok(photoVerificationService.verify(request));
    }

    /** Tone/frustration scoring, kept separate from category-based urgencyScore. */
    @PostMapping("/sentiment")
    public ResponseEntity<SentimentResponse> sentiment(@Valid @RequestBody SentimentRequest request) {
        return ResponseEntity.ok(sentimentUrgencyService.score(request));
    }

    /** Multi-hop, cross-zone causal reasoning — see RootCauseChainService for how this
     *  differs from the single-zone /anomaly-explanation/{zone}. */
    @PostMapping("/root-cause")
    public ResponseEntity<RootCauseResponse> rootCause(@Valid @RequestBody RootCauseRequest request) {
        return ResponseEntity.ok(rootCauseChainService.analyze(request));
    }

    /** Visibility into the classification feedback loop — how many correction exemplars
     *  are currently primed into the classification prompt. Admin tier (see WebMvcConfig). */
    @GetMapping("/classification-feedback")
    public ResponseEntity<com.urban.ai.dto.GenAiDtos.ClassificationFeedbackStats> classificationFeedback() {
        return ResponseEntity.ok(classificationFeedbackService.getStats());
    }

    /** Voice-note complaint intake: transcribe -> detect/translate language -> classify. */
    @PostMapping("/voice-complaint")
    public ResponseEntity<VoiceComplaintResponse> voiceComplaint(@Valid @RequestBody VoiceComplaintRequest request) {
        return ResponseEntity.ok(voiceTranscriptionService.transcribeAndClassify(request));
    }
}
