package com.urban.complaint.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

@Data
public class ComplaintRequest {

    @NotBlank
    @Size(max = 100)
    private String citizenId;

    @NotBlank
    @Size(max = 50)
    private String category;

    @NotBlank
    @Size(max = 2000, message = "description must be 2000 characters or fewer")
    private String description;

    @NotBlank
    @Size(max = 200)
    private String zone;

    @NotNull
    private Double latitude;

    @NotNull
    private Double longitude;

    @Size(max = 10, message = "at most 10 photos per complaint")
    private List<String> photoUrls;

    /** Optional client-supplied idempotency key — see ComplaintService.submit(). */
    @Size(max = 200)
    private String idempotencyKey;
}
