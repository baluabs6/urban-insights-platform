package com.urban.complaint.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.geo.GeoJsonPoint;
import org.springframework.data.mongodb.core.index.GeoSpatialIndexType;
import org.springframework.data.mongodb.core.index.GeoSpatialIndexed;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Document(collection = "citizen_complaints")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CitizenComplaint {

    @Id
    private String id;

    private String citizenId;
    private String category;
    private String description;
    private String zone;
    private String status;
    private String assignedDepartment;

    @GeoSpatialIndexed(type = GeoSpatialIndexType.GEO_2DSPHERE)
    private GeoJsonPoint location;

    private List<String> photoUrls;
    private List<String> tags;
    private Double urgencyScore;
    private String classificationSource;

    private Boolean likelyDuplicate;
    private Double duplicateSimilarityScore;
    private List<String> similarComplaintDescriptions;

    private Boolean photoVerified;
    private String photoVerificationNote;

    private Double sentimentUrgencyScore;
    private String sentimentSummary;

    @Indexed(unique = true, sparse = true)
    private String idempotencyKey;

    @Builder.Default
    private List<ChangeHistoryEntry> history = new ArrayList<>();

    private Instant createdAt;
    private Instant updatedAt;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ChangeHistoryEntry {
        private Instant timestamp;
        private String changeType;
        private String fromValue;
        private String toValue;
        private String actor;
    }
}
