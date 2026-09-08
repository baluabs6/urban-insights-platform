package com.urban.traffic.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class SensorReadingRequest {

    @NotBlank
    private String sensorId;

    @NotBlank
    private String sensorType;

    @NotBlank
    private String zone;

    private Double latitude;
    private Double longitude;

    @NotNull
    private Double value;

    private String unit;
}
