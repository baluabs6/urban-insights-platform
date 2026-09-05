package com.urban.complaint.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.geo.GeoJsonPoint;
import org.springframework.data.mongodb.core.index.GeoSpatialIndexType;
import org.springframework.data.mongodb.core.index.GeoSpatialIndexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.List;

/**
 * Flexible document shape: a pothole complaint and a "garbage not collected"
 * complaint have very different metadata, tags and attachments — Mongo lets each
 * document carry only what's relevant instead of a table full of nullable columns.
 */
@Document(collection = "citizen_complaints")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CitizenComplaint {

    @Id
    private String id;

    private String citizenId;
    private String category;      // POTHOLE, GARBAGE, STREETLIGHT, WATER_LEAKAGE, ENCROACHMENT, NOISE
    private String description;
    private String zone;
    private String status;        // OPEN, IN_PROGRESS, RESOLVED, REJECTED
    private String assignedDepartment;

    @GeoSpatialIndexed(type = GeoSpatialIndexType.GEO_2DSPHERE)
    private GeoJsonPoint location; // [longitude, latitude]

    private List<String> photoUrls;
    private List<String> tags;    // AI-generated tags from GenAI classification
    private Double urgencyScore;  // AI-derived priority score (0-1)
    private String classificationSource; // "AI" or "HEURISTIC_FALLBACK"

    private Boolean likelyDuplicate;
    private Double duplicateSimilarityScore;
    private List<String> similarComplaintDescriptions;

    private Instant createdAt;
    private Instant updatedAt;
}
