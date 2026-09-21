package com.urban.ai.kafka;

import com.urban.ai.client.UrbanDataClient;
import com.urban.ai.dto.GenAiDtos.ClassifyRequest;
import com.urban.ai.dto.GenAiDtos.ClassifyResponse;
import com.urban.ai.genai.ComplaintClassificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class ReclassificationSweepService {

    private final UrbanDataClient dataClient;
    private final ComplaintClassificationService classificationService;

    @Value("${reclassification.sweep-cron:0 */15 * * * *}")
    private String cron;

    @Scheduled(cron = "${reclassification.sweep-cron:0 */15 * * * *}")
    public void sweep() {
        List<Map<String, Object>> pending = dataClient.getComplaintsNeedingReclassification();
        if (pending.isEmpty()) return;

        log.info("Reclassification sweep: {} complaints need (re)classification", pending.size());
        for (Map<String, Object> complaint : pending) {
            String id = String.valueOf(complaint.get("id"));
            try {
                ClassifyRequest request = new ClassifyRequest();
                request.setDescription(String.valueOf(complaint.get("description")));
                request.setZone(String.valueOf(complaint.get("zone")));
                ClassifyResponse classification = classificationService.classify(request);

                if ("AI".equals(classification.getSource())) {
                    Map<String, Object> update = new HashMap<>();
                    update.put("category", classification.getCategory());
                    update.put("department", classification.getDepartment());
                    update.put("urgencyScore", classification.getUrgencyScore());
                    update.put("tags", classification.getTags());
                    update.put("classificationSource", "AI");
                    dataClient.patchClassification(id, update);
                }
            } catch (Exception e) {
                log.warn("Reclassification sweep failed for complaint {}: {}", id, e.getMessage());
            }
        }
    }
}
