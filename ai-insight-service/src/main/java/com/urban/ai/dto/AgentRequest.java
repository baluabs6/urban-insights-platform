package com.urban.ai.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class AgentRequest {

    /** Optional: narrows the question to one zone. */
    @Size(max = 200)
    private String zone;

    @NotBlank
    @Size(max = 1000, message = "question must be 1000 characters or fewer")
    private String question;

    /** Optional: pass the id returned by a previous call to continue that conversation. */
    @Size(max = 100)
    private String conversationId;
}
