package com.urban.ai.genai;

import dev.langchain4j.model.chat.ChatLanguageModel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class LanguageSupportService {

    private final ChatLanguageModel chatLanguageModel;
    private final com.urban.ai.metrics.LlmCallMetrics llmCallMetrics;

    public record DetectionResult(String languageName, String languageCode, String translatedText) {}

    public DetectionResult detectAndTranslateToEnglish(String text) {
        String prompt = """
                Identify the language of the following text and translate it to English.
                Respond with STRICT JSON only, no markdown fences, exactly this shape:
                {"languageName": "<e.g. Hindi, Tamil, English>", "languageCode": "<ISO 639-1, e.g. hi, ta, en>", "translatedText": "<English translation, or original text if already English>"}

                TEXT: "%s"
                """.formatted(text);

        try {
            String raw = llmCallMetrics.time("language_detect_translate", () -> chatLanguageModel.generate(prompt));
            String json = stripFences(raw);
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            var parsed = mapper.readValue(json, java.util.Map.class);
            return new DetectionResult(
                    String.valueOf(parsed.getOrDefault("languageName", "unknown")),
                    String.valueOf(parsed.getOrDefault("languageCode", "unknown")),
                    String.valueOf(parsed.getOrDefault("translatedText", text))
            );
        } catch (Exception e) {
            log.warn("Language detection/translation failed, using original text: {}", e.getMessage());
            return new DetectionResult("unknown", "unknown", text);
        }
    }

    public String translateFromEnglish(String englishText, String targetLanguageName) {
        if (targetLanguageName == null || targetLanguageName.equalsIgnoreCase("English")
                || targetLanguageName.equalsIgnoreCase("unknown")) {
            return englishText;
        }
        String prompt = "Translate the following text into %s. Return ONLY the translated text, nothing else.\n\nTEXT: %s"
                .formatted(targetLanguageName, englishText);
        try {
            return llmCallMetrics.time("language_translate_reverse", () -> chatLanguageModel.generate(prompt));
        } catch (Exception e) {
            log.warn("Reverse translation failed, returning English text: {}", e.getMessage());
            return englishText;
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
