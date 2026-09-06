package com.urban.ai.rag;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.chat.ChatLanguageModel;
import lombok.RequiredArgsConstructor;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * A second LLM pass that checks whether the generated answer is actually
 * supported by the retrieved context, rather than trusting the first pass
 * blindly. Cheap insurance against hallucination — flags low-confidence
 * answers so a human/ops-dashboard can treat them with more skepticism.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class FaithfulnessChecker {

    private final ChatLanguageModel chatLanguageModel;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value
    public static class FaithfulnessResult {
        double score; // 0.0 (unsupported/hallucinated) - 1.0 (fully grounded)
        String rationale;
    }

    public FaithfulnessResult check(String answer, List<String> context) {
        if (context.isEmpty()) {
            return new FaithfulnessResult(0.0, "No context was retrieved to support this answer.");
        }

        String prompt = """
                Given the CONTEXT and the ANSWER below, judge whether the answer is fully
                supported by the context (no invented facts). Respond with STRICT JSON only:
                {"score": <0.0-1.0>, "rationale": "<one short sentence>"}

                CONTEXT:
                - %s

                ANSWER: %s
                """.formatted(String.join("\n- ", context), answer);

        try {
            String raw = chatLanguageModel.generate(prompt);
            String json = stripFences(raw);
            var parsed = objectMapper.readValue(json, java.util.Map.class);
            double score = parsed.get("score") instanceof Number n ? n.doubleValue() : 0.5;
            String rationale = String.valueOf(parsed.getOrDefault("rationale", ""));
            return new FaithfulnessResult(Math.max(0.0, Math.min(1.0, score)), rationale);
        } catch (Exception e) {
            log.warn("Faithfulness check failed, defaulting to neutral score: {}", e.getMessage());
            return new FaithfulnessResult(0.5, "Faithfulness check unavailable (AI error).");
        }
    }

    private String stripFences(String raw) {
        String trimmed = raw.trim();
        if (trimmed.startsWith("```")) {
            trimmed = trimmed.replaceAll("^```(json)?", "").replaceAll("```$", "").trim();
        }
        return trimmed;
    }
}
