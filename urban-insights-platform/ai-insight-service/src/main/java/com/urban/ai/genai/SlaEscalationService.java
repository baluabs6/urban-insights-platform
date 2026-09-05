package com.urban.ai.genai;

import com.urban.ai.client.UrbanDataClient;
import dev.langchain4j.model.chat.ChatLanguageModel;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Scans open complaints and flags any that have breached a per-category SLA
 * (e.g. a pothole open for more than 5 days), then drafts an escalation note
 * a supervisor could send to the responsible department — turning "someone
 * has to remember to check this" into an automated daily sweep.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SlaEscalationService {

    private final UrbanDataClient dataClient;
    private final ChatLanguageModel chatLanguageModel;

    @Value("${sla.hours.default:120}") // 5 days
    private long defaultSlaHours;

    private static final Map<String, Long> CATEGORY_SLA_HOURS = Map.of(
            "POTHOLE", 120L,
            "GARBAGE", 24L,
            "STREETLIGHT", 72L,
            "WATER_LEAKAGE", 48L,
            "ENCROACHMENT", 240L,
            "NOISE", 48L
    );

    @Data
    @Builder
    public static class EscalationItem {
        private String complaintId;
        private String category;
        private String zone;
        private String assignedDepartment;
        private long hoursOpen;
        private long slaHours;
        private String escalationDraft;
    }

    /** Runs every day at 08:00 — checks OPEN and IN_PROGRESS complaints for SLA breaches. */
    @Scheduled(cron = "${sla.check-cron:0 0 8 * * *}")
    public void scheduledSlaSweep() {
        List<EscalationItem> breaches = checkBreaches();
        if (!breaches.isEmpty()) {
            log.warn("SLA sweep found {} breaching complaints", breaches.size());
        }
    }

    @SuppressWarnings("unchecked")
    public List<EscalationItem> checkBreaches() {
        List<Map<String, Object>> open = new java.util.ArrayList<>();
        open.addAll(dataClient.getComplaintsByStatus("OPEN"));
        open.addAll(dataClient.getComplaintsByStatus("IN_PROGRESS"));

        return open.stream()
                .map(this::toBreachOrNull)
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toList());
    }

    private EscalationItem toBreachOrNull(Map<String, Object> complaint) {
        String category = String.valueOf(complaint.get("category"));
        long slaHours = CATEGORY_SLA_HOURS.getOrDefault(category, defaultSlaHours);

        Object createdAtObj = complaint.get("createdAt");
        if (createdAtObj == null) return null;

        Instant createdAt;
        try {
            createdAt = Instant.parse(String.valueOf(createdAtObj));
        } catch (Exception e) {
            return null;
        }

        long hoursOpen = Duration.between(createdAt, Instant.now()).toHours();
        if (hoursOpen < slaHours) return null;

        String draft = draftEscalation(complaint, hoursOpen, slaHours);

        return EscalationItem.builder()
                .complaintId(String.valueOf(complaint.get("id")))
                .category(category)
                .zone(String.valueOf(complaint.get("zone")))
                .assignedDepartment(String.valueOf(complaint.get("assignedDepartment")))
                .hoursOpen(hoursOpen)
                .slaHours(slaHours)
                .escalationDraft(draft)
                .build();
    }

    private String draftEscalation(Map<String, Object> complaint, long hoursOpen, long slaHours) {
        String prompt = """
                Draft a brief, firm-but-professional escalation email (3-4 sentences) to the
                department below, noting this complaint has exceeded its SLA and needs
                immediate attention.

                Department: %s
                Category: %s
                Zone: %s
                Description: %s
                Hours open: %d (SLA: %d hours)
                """.formatted(
                complaint.get("assignedDepartment"), complaint.get("category"), complaint.get("zone"),
                complaint.get("description"), hoursOpen, slaHours);

        try {
            return chatLanguageModel.generate(prompt);
        } catch (Exception e) {
            return String.format(
                    "Complaint %s (%s) in %s has been open %d hours, exceeding the %d-hour SLA. Please action urgently.",
                    complaint.get("id"), complaint.get("category"), complaint.get("zone"), hoursOpen, slaHours);
        }
    }
}
