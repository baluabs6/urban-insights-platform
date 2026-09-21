package com.urban.ai.genai;

import com.urban.ai.dto.GenAiDtos.ClassificationFeedbackStats;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class ClassificationFeedbackService {

    private final RedisTemplate<String, Object> redisTemplate;

    private static final String EXEMPLAR_KEY = "classification-feedback:exemplars";
    private static final int MAX_EXEMPLARS = 20;
    private static final int FEW_SHOT_EXAMPLES_IN_PROMPT = 5;

    public void recordCorrection(Map<String, Object> event) {
        String description = String.valueOf(event.getOrDefault("description", ""));
        String previousCategory = String.valueOf(event.getOrDefault("previousCategory", ""));
        String correctedCategory = String.valueOf(event.getOrDefault("correctedCategory", ""));
        Object note = event.get("correctionNote");

        if (previousCategory.equalsIgnoreCase(correctedCategory)) {
            return;
        }

        String exemplar = "Complaint: \"%s\" -> WRONG: %s, CORRECT: %s%s".formatted(
                truncate(description, 200), previousCategory, correctedCategory,
                note != null && !String.valueOf(note).isBlank() ? " (reason: " + note + ")" : "");

        redisTemplate.opsForList().leftPush(EXEMPLAR_KEY, exemplar);
        redisTemplate.opsForList().trim(EXEMPLAR_KEY, 0, MAX_EXEMPLARS - 1);
        log.info("Recorded classification-correction exemplar: {} -> {}", previousCategory, correctedCategory);
    }

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
