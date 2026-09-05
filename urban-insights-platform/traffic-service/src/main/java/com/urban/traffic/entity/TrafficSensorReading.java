package com.urban.traffic.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * A single reading from a city sensor (traffic camera/loop detector, AQI station,
 * noise meter, water-level sensor, etc.) — the structured "system of record" table.
 */
@Entity
@Table(name = "sensor_readings", indexes = {
        @Index(name = "idx_sensor_id_time", columnList = "sensorId,recordedAt"),
        @Index(name = "idx_zone", columnList = "zone")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TrafficSensorReading {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String sensorId;

    @Column(nullable = false)
    private String sensorType; // TRAFFIC_FLOW, AQI, NOISE, WATER_LEVEL, PARKING

    @Column(nullable = false)
    private String zone; // e.g. "Koramangala", "Connaught Place"

    private Double latitude;
    private Double longitude;

    @Column(nullable = false)
    private Double value; // vehicle count / AQI index / dB / cm etc.

    private String unit;

    @Column(nullable = false)
    private Instant recordedAt;

    @Builder.Default
    private Boolean anomaly = false;

    private Double anomalyScore;
}
