package com.urban.complaint.controller;

import com.urban.complaint.dto.ClassificationUpdateRequest;
import com.urban.complaint.dto.ComplaintRequest;
import com.urban.complaint.entity.CitizenComplaint;
import com.urban.complaint.service.ComplaintService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.geo.GeoResults;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/complaints")
@RequiredArgsConstructor
public class ComplaintController {

    private final ComplaintService service;

    @PostMapping
    public ResponseEntity<CitizenComplaint> submit(@Valid @RequestBody ComplaintRequest request) {
        return ResponseEntity.ok(service.submit(request));
    }

    /** Internal callback used by ai-insight-service once async classification completes. */
    @PatchMapping("/{id}/classification")
    public ResponseEntity<CitizenComplaint> updateClassification(
            @PathVariable String id, @Valid @RequestBody ClassificationUpdateRequest update) {
        return ResponseEntity.ok(service.updateClassification(id, update));
    }

    @PatchMapping("/{id}/status")
    public ResponseEntity<CitizenComplaint> updateStatus(@PathVariable String id, @RequestParam String status) {
        return ResponseEntity.ok(service.updateStatus(id, status));
    }

    @GetMapping("/{id}")
    public ResponseEntity<CitizenComplaint> byId(@PathVariable String id) {
        return service.byId(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /** Anything still on heuristic classification — used by ai-insight-service's reclassification sweep. */
    @GetMapping("/needing-reclassification")
    public ResponseEntity<Page<CitizenComplaint>> needingReclassification(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(service.findNeedingReclassification(PageRequest.of(page, Math.min(size, 100))));
    }

    @GetMapping("/status/{status}")
    public ResponseEntity<Page<CitizenComplaint>> byStatus(
            @PathVariable String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(service.byStatus(status, PageRequest.of(page, Math.min(size, 100))));
    }

    @GetMapping("/zone/{zone}")
    public ResponseEntity<Page<CitizenComplaint>> byZone(
            @PathVariable String zone,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(service.byZone(zone, PageRequest.of(page, Math.min(size, 100))));
    }

    @GetMapping("/urgent")
    public ResponseEntity<List<CitizenComplaint>> topUrgent() {
        return ResponseEntity.ok(service.topUrgent());
    }

    @GetMapping("/nearby")
    public ResponseEntity<GeoResults<CitizenComplaint>> nearby(
            @RequestParam double lat, @RequestParam double lon,
            @RequestParam(defaultValue = "2.0") double radiusKm) {
        return ResponseEntity.ok(service.nearby(lat, lon, radiusKm));
    }
}
