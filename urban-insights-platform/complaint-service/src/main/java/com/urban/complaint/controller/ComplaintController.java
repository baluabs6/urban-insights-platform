package com.urban.complaint.controller;

import com.urban.complaint.dto.ComplaintRequest;
import com.urban.complaint.entity.CitizenComplaint;
import com.urban.complaint.service.ComplaintService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
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

    @GetMapping("/status/{status}")
    public ResponseEntity<List<CitizenComplaint>> byStatus(@PathVariable String status) {
        return ResponseEntity.ok(service.byStatus(status));
    }

    @GetMapping("/zone/{zone}")
    public ResponseEntity<List<CitizenComplaint>> byZone(@PathVariable String zone) {
        return ResponseEntity.ok(service.byZone(zone));
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
