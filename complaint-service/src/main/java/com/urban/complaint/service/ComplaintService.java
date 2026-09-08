package com.urban.complaint.service;

import com.urban.complaint.config.KafkaProducerConfig;
import com.urban.complaint.dto.ClassificationUpdateRequest;
import com.urban.complaint.dto.ComplaintRequest;
import com.urban.complaint.entity.CitizenComplaint;
import com.urban.complaint.entity.CitizenComplaint.ChangeHistoryEntry;
import com.urban.complaint.repository.ComplaintRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.geo.Metrics;
import org.springframework.data.mongodb.core.geo.GeoJsonPoint;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
public class ComplaintService {

    private final ComplaintRepository repository;
    private final KafkaTemplate<String, Object> kafkaTemplate;

    private static final Map<String, String> DEPARTMENT_ROUTING = Map.of(
            "POTHOLE", "ROADS_AND_INFRASTRUCTURE",
            "GARBAGE", "SANITATION",
            "STREETLIGHT", "ELECTRICAL",
            "WATER_LEAKAGE", "WATER_BOARD",
            "ENCROACHMENT", "URBAN_PLANNING",
            "NOISE", "POLLUTION_CONTROL"
    );

    /**
     * Submission is now fast and fully local: save immediately with a cheap
     * heuristic classification (classificationSource=PENDING_AI), then publish
     * a "complaint.created" event. ai-insight-service consumes it asynchronously,
     * runs the real LLM classification + embedding-based duplicate check (which
     * used to happen synchronously here, blocking the citizen on 2+ LLM round
     * trips), and PATCHes the real result back via updateClassification().
     *
     * If ai-insight-service or Kafka is ever down, the complaint still exists
     * with a usable heuristic classification — it just won't get refined until
     * the AI service catches up (see ai-insight-service's reclassification
     * sweep, which re-scans anything still PENDING_AI/HEURISTIC_FALLBACK).
     *
     * Idempotency: if the caller supplies idempotencyKey (e.g. a UUID the
     * mobile app generates once before its first submit attempt) and a
     * complaint with that key already exists, that existing complaint is
     * returned instead of creating a second one — closes the "network retry
     * creates a duplicate complaint" gap. Complaints without a key behave as
     * before (each POST creates a new complaint).
     */
    public CitizenComplaint submit(ComplaintRequest request) {
        if (request.getIdempotencyKey() != null && !request.getIdempotencyKey().isBlank()) {
            Optional<CitizenComplaint> existing = repository.findByIdempotencyKey(request.getIdempotencyKey());
            if (existing.isPresent()) {
                log.info("Duplicate submission detected via idempotencyKey {} -> returning existing complaint {}",
                        request.getIdempotencyKey(), existing.get().getId());
                return existing.get();
            }
        }

        Instant now = Instant.now();
        String heuristicCategory = heuristicCategory(request.getDescription(), request.getCategory());

        CitizenComplaint complaint = CitizenComplaint.builder()
                .citizenId(request.getCitizenId())
                .category(heuristicCategory)
                .description(request.getDescription())
                .zone(request.getZone())
                .status("OPEN")
                .assignedDepartment(DEPARTMENT_ROUTING.getOrDefault(heuristicCategory, "GENERAL_CIVIC"))
                .location(new GeoJsonPoint(request.getLongitude(), request.getLatitude()))
                .photoUrls(request.getPhotoUrls())
                .tags(List.of(heuristicCategory.toLowerCase()))
                .urgencyScore(heuristicUrgency(request.getDescription()))
                .classificationSource("PENDING_AI")
                .likelyDuplicate(false)
                .idempotencyKey(request.getIdempotencyKey())
                .history(new ArrayList<>(List.of(ChangeHistoryEntry.builder()
                        .timestamp(now)
                        .changeType("STATUS")
                        .fromValue(null)
                        .toValue("OPEN")
                        .actor("citizen")
                        .build())))
                .createdAt(now)
                .updatedAt(now)
                .build();

        CitizenComplaint saved;
        try {
            saved = repository.save(complaint);
        } catch (org.springframework.dao.DuplicateKeyException e) {
            // Race: two requests with the same idempotencyKey arrived concurrently and
            // both passed the findByIdempotencyKey check above before either saved.
            // The unique index is the real guard; on conflict, return the winner.
            log.info("Concurrent duplicate submission for idempotencyKey {} — returning the winning record",
                    request.getIdempotencyKey());
            return repository.findByIdempotencyKey(request.getIdempotencyKey())
                    .orElseThrow(() -> e);
        }

        try {
            kafkaTemplate.send(KafkaProducerConfig.TOPIC_COMPLAINT_CREATED, saved.getId(), saved)
                    .whenComplete((result, ex) -> {
                        if (ex != null) {
                            // Not silently lost: this complaint keeps its usable heuristic
                            // classification (classificationSource=PENDING_AI) and WILL be
                            // picked up by ai-insight-service's reclassification sweep —
                            // logged here so an operator can also see it happened in real time.
                            log.error("Async publish of complaint.created failed for {} after send() returned: {}. " +
                                    "Will be caught by the reclassification sweep.", saved.getId(), ex.getMessage(), ex);
                        }
                    });
        } catch (Exception e) {
            // Submission already succeeded and is usable; async enrichment can catch up
            // later via the reclassification sweep even if the publish itself fails.
            log.warn("Failed to publish complaint.created event for {}: {}", saved.getId(), e.getMessage());
        }

        return saved;
    }

    /** Called back by ai-insight-service once async classification/duplicate-check completes. */
    public CitizenComplaint updateClassification(String id, ClassificationUpdateRequest update) {
        CitizenComplaint complaint = repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Complaint not found: " + id));

        String previousCategory = complaint.getCategory();

        complaint.setCategory(update.getCategory());
        complaint.setAssignedDepartment(update.getDepartment());
        complaint.setUrgencyScore(update.getUrgencyScore());
        complaint.setTags(update.getTags());
        complaint.setClassificationSource(update.getClassificationSource());
        complaint.setLikelyDuplicate(update.getLikelyDuplicate());
        complaint.setDuplicateSimilarityScore(update.getDuplicateSimilarityScore());
        complaint.setSimilarComplaintDescriptions(update.getSimilarComplaintDescriptions());
        complaint.setUpdatedAt(Instant.now());

        appendHistory(complaint, "CLASSIFICATION", previousCategory, update.getCategory(), "system");

        return repository.save(complaint);
    }

    /** actor: caller identity from the X-Caller-Id header (see ComplaintController) — "unknown" if not supplied. */
    public CitizenComplaint updateStatus(String id, String status, String actor) {
        CitizenComplaint complaint = repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Complaint not found: " + id));
        String previousStatus = complaint.getStatus();
        complaint.setStatus(status);
        complaint.setUpdatedAt(Instant.now());
        appendHistory(complaint, "STATUS", previousStatus, status, actor);
        return repository.save(complaint);
    }

    private void appendHistory(CitizenComplaint complaint, String changeType, String from, String to, String actor) {
        if (complaint.getHistory() == null) {
            complaint.setHistory(new ArrayList<>());
        }
        complaint.getHistory().add(ChangeHistoryEntry.builder()
                .timestamp(Instant.now())
                .changeType(changeType)
                .fromValue(from)
                .toValue(to)
                .actor(actor != null ? actor : "unknown")
                .build());
    }

    public Page<CitizenComplaint> byZone(String zone, Pageable pageable) {
        return repository.findByZone(zone, pageable);
    }

    public Optional<CitizenComplaint> byId(String id) {
        return repository.findById(id);
    }

    public Page<CitizenComplaint> byStatus(String status, Pageable pageable) {
        return repository.findByStatus(status, pageable);
    }

    /** Still unbounded by design — small, curated top-N list for dashboards/briefings. */
    public List<CitizenComplaint> topUrgent() {
        return repository.findTop20ByOrderByUrgencyScoreDesc();
    }

    public GeoResults<CitizenComplaint> nearby(double lat, double lon, double radiusKm) {
        return repository.findNear(new GeoJsonPoint(lon, lat), new Distance(radiusKm, Metrics.KILOMETERS));
    }

    /** Anything still awaiting or stuck on heuristic classification — used by the reclassification sweep. */
    public Page<CitizenComplaint> findNeedingReclassification(Pageable pageable) {
        return repository.findByClassificationSourceIn(List.of("PENDING_AI", "HEURISTIC_FALLBACK"), pageable);
    }

    // Package-private for unit testing (see ComplaintServiceTest).
    String heuristicCategory(String description, String suppliedCategory) {
        if (suppliedCategory != null && !suppliedCategory.isBlank()) return suppliedCategory.toUpperCase();
        String text = description.toLowerCase();
        if (text.contains("pothole") || text.contains("road")) return "POTHOLE";
        if (text.contains("garbage") || text.contains("trash")) return "GARBAGE";
        if (text.contains("light")) return "STREETLIGHT";
        if (text.contains("water") || text.contains("leak")) return "WATER_LEAKAGE";
        if (text.contains("encroach")) return "ENCROACHMENT";
        if (text.contains("noise") || text.contains("loud")) return "NOISE";
        return "OTHER";
    }

    // Package-private for unit testing (see ComplaintServiceTest).
    Double heuristicUrgency(String description) {
        String text = description.toLowerCase();
        if (text.contains("danger") || text.contains("accident") || text.contains("sewage")) return 0.9;
        if (text.contains("overflow") || text.contains("broken")) return 0.6;
        return 0.3;
    }
}
