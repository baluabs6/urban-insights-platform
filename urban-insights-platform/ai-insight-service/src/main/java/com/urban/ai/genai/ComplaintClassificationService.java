package com.urban.ai.genai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.urban.ai.dto.GenAiDtos.ClassifyRequest;
import com.urban.ai.dto.GenAiDtos.ClassifyResponse;
import dev.langchain4j.model.chat.ChatLanguageModel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * Replaces the keyword heuristic in complaint-service with an LLM call: given a
 * citizen's free-text description (in any language — see LanguageSupportService),
 * ask the model to return category, department, an urgency score and tags as
 * strict JSON.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ComplaintClassificationService {

    private final ChatLanguageModel chatLanguageModel;
    private final LanguageSupportService languageSupportService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final List<String> VALID_CATEGORIES = List.of(
            "POTHOLE", "GARBAGE", "STREETLIGHT", "WATER_LEAKAGE", "ENCROACHMENT", "NOISE", "OTHER");

    private static final Map<String, String> DEPARTMENT_ROUTING = Map.of(
            "POTHOLE", "ROADS_AND_INFRASTRUCTURE",
            "GARBAGE", "SANITATION",
            "STREETLIGHT", "ELECTRICAL",
            "WATER_LEAKAGE", "WATER_BOARD",
            "ENCROACHMENT", "URBAN_PLANNING",
            "NOISE", "POLLUTION_CONTROL",
            "OTHER", "GENERAL_CIVIC"
    );

    public ClassifyResponse classify(ClassifyRequest request) {
        // Detect language and translate to English first so classification quality
        // doesn't depend on the LLM's fluency in the citizen's original language.
        LanguageSupportService.DetectionResult detected =
                languageSupportService.detectAndTranslateToEnglish(request.getDescription());

        if (!"en".equalsIgnoreCase(detected.languageCode()) && !"unknown".equals(detected.languageCode())) {
            log.info("Complaint description detected as {} — translated for classification", detected.languageName());
        }

        String descriptionForClassification = detected.translatedText();

        String prompt = """
                Classify the following citizen civic complaint. Respond with STRICT JSON only,
                no markdown fences, no extra text, matching exactly this shape:
                {"category": "<one of %s>", "urgencyScore": <number 0.0-1.0>, "tags": ["..."], "reasoning": "<one short sentence>"}

                Complaint zone: %s
                Complaint description: "%s"
                """.formatted(VALID_CATEGORIES, request.getZone(), descriptionForClassification);

        try {
            String raw = chatLanguageModel.generate(prompt);
            String json = extractJson(raw);
            Map<String, Object> parsed = objectMapper.readValue(json, Map.class);

            String category = String.valueOf(parsed.getOrDefault("category", "OTHER")).toUpperCase();
            if (!VALID_CATEGORIES.contains(category)) category = "OTHER";

            double urgency = parsed.get("urgencyScore") instanceof Number n ? n.doubleValue() : 0.3;
            @SuppressWarnings("unchecked")
            List<String> tags = parsed.get("tags") instanceof List<?> l
                    ? l.stream().map(String::valueOf).toList()
                    : List.of(category.toLowerCase());

            return ClassifyResponse.builder()
                    .category(category)
                    .department(DEPARTMENT_ROUTING.getOrDefault(category, "GENERAL_CIVIC"))
                    .urgencyScore(clamp(urgency))
                    .tags(tags)
                    .reasoning(String.valueOf(parsed.getOrDefault("reasoning", "")))
                    .detectedLanguage(detected.languageName())
                    .build();

        } catch (Exception e) {
            log.warn("LLM classification failed, falling back to heuristic: {}", e.getMessage());
            return heuristicFallback(request);
        }
    }

    /** Same-shape fallback so callers never have to special-case AI downtime. */
    private ClassifyResponse heuristicFallback(ClassifyRequest request) {
        String text = request.getDescription().toLowerCase();
        String category = "OTHER";
        if (text.contains("pothole") || text.contains("road")) category = "POTHOLE";
        else if (text.contains("garbage") || text.contains("trash")) category = "GARBAGE";
        else if (text.contains("light")) category = "STREETLIGHT";
        else if (text.contains("water") || text.contains("leak")) category = "WATER_LEAKAGE";
        else if (text.contains("encroach")) category = "ENCROACHMENT";
        else if (text.contains("noise") || text.contains("loud")) category = "NOISE";

        double urgency = (text.contains("danger") || text.contains("accident") || text.contains("sewage")) ? 0.9
                : (text.contains("overflow") || text.contains("broken")) ? 0.6 : 0.3;

        return ClassifyResponse.builder()
                .category(category)
                .department(DEPARTMENT_ROUTING.getOrDefault(category, "GENERAL_CIVIC"))
                .urgencyScore(urgency)
                .tags(List.of(category.toLowerCase()))
                .reasoning("heuristic fallback (AI model unavailable)")
                .detectedLanguage("unknown")
                .build();
    }

    private double clamp(double v) {
        return Math.max(0.0, Math.min(1.0, v));
    }

    /** Strips accidental markdown fences some models add despite instructions. */
    private String extractJson(String raw) {
        String trimmed = raw.trim();
        if (trimmed.startsWith("```")) {
            trimmed = trimmed.replaceAll("^```(json)?", "").replaceAll("```$", "").trim();
        }
        return trimmed;
    }
}
