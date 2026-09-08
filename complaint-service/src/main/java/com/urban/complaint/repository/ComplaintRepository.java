package com.urban.complaint.repository;

import com.urban.complaint.entity.CitizenComplaint;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.mongodb.core.geo.GeoJsonPoint;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;

import java.util.List;

public interface ComplaintRepository extends MongoRepository<CitizenComplaint, String> {

    // Paginated versions — a busy zone/status can accumulate thousands of documents,
    // and returning them all in one response blows up memory and (when fed to an
    // LLM prompt) the model's context window.
    Page<CitizenComplaint> findByZone(String zone, Pageable pageable);
    Page<CitizenComplaint> findByStatus(String status, Pageable pageable);

    List<CitizenComplaint> findByCategory(String category);

    @Query("{ 'location' : { $near : { $geometry : ?0, $maxDistance : ?1 } } }")
    GeoResults<CitizenComplaint> findNear(GeoJsonPoint point, Distance maxDistance);

    List<CitizenComplaint> findTop20ByOrderByUrgencyScoreDesc();

    Page<CitizenComplaint> findByClassificationSourceIn(List<String> sources, Pageable pageable);

    java.util.Optional<CitizenComplaint> findByIdempotencyKey(String idempotencyKey);
}
