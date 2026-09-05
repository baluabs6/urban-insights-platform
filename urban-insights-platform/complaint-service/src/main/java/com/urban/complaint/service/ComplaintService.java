package com.urban.complaint.service;

import com.urban.complaint.client.AiInsightClient;
import com.urban.complaint.client.AiInsightClient.ClassifyResult;
import com.urban.complaint.client.AiInsightClient.DuplicateResult;
import com.urban.complaint.dto.ComplaintRequest;
import com.urban.complaint.entity.CitizenComplaint;
import com.urban.complaint.repository.ComplaintRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.geo.Metrics;
import org.springframework.data.mongodb.core.geo.GeoJsonPoint;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class ComplaintService {

    private final ComplaintRepository repository;
    private final AiInsightClient aiInsightClient;

    private static final Map<String, String> DEPARTMENT_ROUTING = Map.of(
            "POTHOLE", "ROADS_AND_INFRASTRUCTURE",
            "GARBAGE", "SANITATION",
            "STREETLIGHT", "ELECTRICAL",
            "WATER_LEAKAGE", "WATER_BOARD",
            "ENCROACHMENT", "URBAN_PLANNING",
            "NOISE", "POLLUTION_CONTROL"
    );

    /**
     * `urgencyScore`, `tags` and department routing now come from the
     * ai-insight-service's GenAI classifier (reading the free-text description),
     * with a local heuristic fallback if that service is unavailable — submission
     * never fails just because the AI layer is down. A duplicate check against
     * other open complaints in the same zone runs in parallel via embeddings.
     */
    public CitizenComplaint submit(ComplaintRequest request) {
        Instant now = Instant.now();

        ClassifyResult classification = aiInsightClient.classify(request.getDescription(), request.getZone());
        DuplicateResult duplicateResult = aiInsightClient.duplicateCheck(request.getDescription(), request.getZone());

        String category = classification != null ? classification.getCategory() : request.getCategory();
        String department = classification != null
                ? classification.getDepartment()
                : DEPARTMENT_ROUTING.getOrDefault(request.getCategory(), "GENERAL_CIVIC");
        Double urgency = classification != null ? classification.getUrgencyScore() : heuristicUrgency(request.getDescription());
        List<String> tags = classification != null && classification.getTags() != null
                ? classification.getTags()
                : List.of(request.getCategory().toLowerCase());
        String source = classification != null ? "AI" : "HEURISTIC_FALLBACK";

        CitizenComplaint complaint = CitizenComplaint.builder()
                .citizenId(request.getCitizenId())
                .category(category)
                .description(request.getDescription())
                .zone(request.getZone())
                .status("OPEN")
                .assignedDepartment(department)
                .location(new GeoJsonPoint(request.getLongitude(), request.getLatitude()))
                .photoUrls(request.getPhotoUrls())
                .tags(tags)
                .urgencyScore(urgency)
                .classificationSource(source)
                .likelyDuplicate(duplicateResult != null && duplicateResult.isDuplicate())
                .duplicateSimilarityScore(duplicateResult != null ? duplicateResult.getMaxSimilarityScore() : null)
                .similarComplaintDescriptions(duplicateResult != null ? duplicateResult.getSimilarComplaints() : null)
                .createdAt(now)
                .updatedAt(now)
                .build();

        return repository.save(complaint);
    }

    public CitizenComplaint updateStatus(String id, String status) {
        CitizenComplaint complaint = repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Complaint not found: " + id));
        complaint.setStatus(status);
        complaint.setUpdatedAt(Instant.now());
        return repository.save(complaint);
    }

    public List<CitizenComplaint> byZone(String zone) {
        return repository.findByZone(zone);
    }

    public java.util.Optional<CitizenComplaint> byId(String id) {
        return repository.findById(id);
    }

    public List<CitizenComplaint> byStatus(String status) {
        return repository.findByStatus(status);
    }

    public List<CitizenComplaint> topUrgent() {
        return repository.findTop20ByOrderByUrgencyScoreDesc();
    }

    public GeoResults<CitizenComplaint> nearby(double lat, double lon, double radiusKm) {
        return repository.findNear(new GeoJsonPoint(lon, lat), new Distance(radiusKm, Metrics.KILOMETERS));
    }

    /** Placeholder for the real GenAI urgency classifier — keyword heuristic only. */
    private Double heuristicUrgency(String description) {
        String text = description.toLowerCase();
        if (text.contains("danger") || text.contains("accident") || text.contains("sewage")) return 0.9;
        if (text.contains("overflow") || text.contains("broken")) return 0.6;
        return 0.3;
    }
}
