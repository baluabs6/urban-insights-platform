package com.urban.complaint.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

@Data
public class ComplaintRequest {

    @NotBlank
    private String citizenId;

    @NotBlank
    private String category;

    @NotBlank
    private String description;

    @NotBlank
    private String zone;

    @NotNull
    private Double latitude;

    @NotNull
    private Double longitude;

    private List<String> photoUrls;
}
