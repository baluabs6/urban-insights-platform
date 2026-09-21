package com.urban.traffic.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

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
    private String sensorType;

    @Column(nullable = false)
    private String zone;

    private Double latitude;
    private Double longitude;

    @Column(nullable = false)
    private Double value;

    private String unit;

    @Column(nullable = false)
    private Instant recordedAt;

    @Builder.Default
    private Boolean anomaly = false;

    private Double anomalyScore;
}
