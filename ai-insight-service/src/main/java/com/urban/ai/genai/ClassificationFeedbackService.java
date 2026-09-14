package com.urban.ai.genai;

import com.urban.ai.dto.GenAiDtos.ClassificationFeedbackStats;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * Feedback-loop AI module: turns ops corrections (complaint-service's
 * /classification/override, published as "complaint.classification.overridden")
 * into few-shot exemplars fed back into ComplaintClassificationService's prompt —
 * closing the "no mechanism to learn from corrections" gap. Not fine-tuning (no
 * training pipeline in this reference project), but a real, working feedback
 * loop: the more ops corrects a given mistake pattern, the more likely the next
 * classification call sees a matching example and gets it right the first time.
 *
 * Stored as a bounded Redis LIST (most recent first) rather than growing
 * unboundedly — old corrections age out once MAX_EXEMPLARS is exceeded.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ClassificationFeedbackService {

    private final RedisTemplate<String, Object> redisTemplate;

    private static final String EXEMPLAR_KEY = "classification-feedback:exemplars";
    private static final int MAX_EXEMPLARS = 20;
    private static final int FEW_SHOT_EXAMPLES_IN_PROMPT = 5; // keep the prompt short — most recent corrections

    public void recordCorrection(Map<String, Object> event) {
        String description = String.valueOf(event.getOrDefault("description", ""));
        String previousCategory = String.valueOf(event.getOrDefault("previousCategory", ""));
        String correctedCategory = String.valueOf(event.getOrDefault("correctedCategory", ""));
        Object note = event.get("correctionNote");

        if (previousCategory.equalsIgnoreCase(correctedCategory)) {
            // Ops only touched department/urgency, not the category itself — nothing
            // useful to teach the classifier's category prompt from this one.
            return;
        }

        String exemplar = "Complaint: \"%s\" -> WRONG: %s, CORRECT: %s%s".formatted(
                truncate(description, 200), previousCategory, correctedCategory,
                note != null && !String.valueOf(note).isBlank() ? " (reason: " + note + ")" : "");

        redisTemplate.opsForList().leftPush(EXEMPLAR_KEY, exemplar);
        redisTemplate.opsForList().trim(EXEMPLAR_KEY, 0, MAX_EXEMPLARS - 1);
        log.info("Recorded classification-correction exemplar: {} -> {}", previousCategory, correctedCategory);
    }

    /** Used by ComplaintClassificationService to few-shot-prime future classifications. */
    @SuppressWarnings("unchecked")
    public String buildFewShotBlock() {
        List<Object> raw = redisTemplate.opsForList().range(EXEMPLAR_KEY, 0, FEW_SHOT_EXAMPLES_IN_PROMPT - 1);
        if (raw == null || raw.isEmpty()) return "";

        StringBuilder block = new StringBuilder("\nRecent human corrections to learn from (do NOT repeat these mistakes):\n");
        for (Object o : raw) {
            block.append("- ").append(o).append("\n");
        }
        return block.toString();
    }

    @SuppressWarnings("unchecked")
    public ClassificationFeedbackStats getStats() {
        List<Object> raw = redisTemplate.opsForList().range(EXEMPLAR_KEY, 0, -1);
        List<String> recent = raw == null ? List.of() : raw.stream().map(String::valueOf).toList();
        return ClassificationFeedbackStats.builder()
                .exemplarCount(recent.size())
                .recentCorrections(recent)
                .build();
    }

    private String truncate(String s, int max) {
        return s.length() > max ? s.substring(0, max) + "..." : s;
    }
}
