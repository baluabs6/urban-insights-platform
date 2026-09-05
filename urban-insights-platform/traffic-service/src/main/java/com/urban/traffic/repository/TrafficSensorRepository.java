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

    List<TrafficSensorReading> findByZone(String zone);
}
