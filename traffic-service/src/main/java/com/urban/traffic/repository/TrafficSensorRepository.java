package com.urban.traffic.repository;

import com.urban.traffic.entity.TrafficSensorReading;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface TrafficSensorRepository extends JpaRepository<TrafficSensorReading, Long> {

    List<TrafficSensorReading> findTop50BySensorIdOrderByRecordedAtDesc(String sensorId);

    List<TrafficSensorReading> findByZoneAndRecordedAtBetween(String zone, Instant from, Instant to);

    @Query("SELECT AVG(r.value) FROM TrafficSensorReading r WHERE r.sensorId = :sensorId AND r.recordedAt >= :since")
    Double averageValueSince(@Param("sensorId") String sensorId, @Param("since") Instant since);

    @Query("SELECT STDDEV(r.value) FROM TrafficSensorReading r WHERE r.sensorId = :sensorId AND r.recordedAt >= :since")
    Double stdDevSince(@Param("sensorId") String sensorId, @Param("since") Instant since);

    List<TrafficSensorReading> findByAnomalyTrueAndRecordedAtAfter(Instant since);

    org.springframework.data.domain.Page<TrafficSensorReading> findByAnomalyTrueAndRecordedAtAfter(
            Instant since, org.springframework.data.domain.Pageable pageable);

    org.springframework.data.domain.Page<TrafficSensorReading> findByZone(String zone, org.springframework.data.domain.Pageable pageable);

    // Aggregate queries so zone-summary doesn't have to load every row for a zone
    // into memory just to compute an average/count (a real gap in a zone that
    // accumulates millions of readings over time).
    @Query("SELECT COUNT(r) FROM TrafficSensorReading r WHERE r.zone = :zone")
    long countByZone(@Param("zone") String zone);

    @Query("SELECT AVG(r.value) FROM TrafficSensorReading r WHERE r.zone = :zone")
    Double averageValueByZone(@Param("zone") String zone);

    @Query("SELECT COUNT(r) FROM TrafficSensorReading r WHERE r.zone = :zone AND r.anomaly = true")
    long countAnomaliesByZone(@Param("zone") String zone);

    // Feeds ForecastingService: recent history per zone, grouped/sorted in-service
    // by sensorId so each sensor's short-term trend is projected independently
    // before being combined into a zone-level forecast.
    List<TrafficSensorReading> findByZoneAndRecordedAtBetweenOrderByRecordedAtAsc(
            String zone, Instant from, Instant to);
}
