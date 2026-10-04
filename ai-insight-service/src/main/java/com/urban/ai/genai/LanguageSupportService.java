package com.urban.ai.genai;

import com.urban.ai.llm.LlmClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class LanguageSupportService {

    private final LlmClient llm;

    public record DetectionResult(String languageName, String languageCode, String translatedText) {}

    /** Structured-output target: Spring AI derives the JSON schema from this record. */
    public record LanguageDetection(String languageName, String languageCode, String translatedText) {}

    public DetectionResult detectAndTranslateToEnglish(String text) {
        String prompt = """
                Identify the language of the following text and translate it to English.
                "languageName" is e.g. Hindi, Tamil, English; "languageCode" is ISO 639-1 (hi, ta, en);
                "translatedText" is the English translation, or the original text if it is already English.

                TEXT: "%s"
                """.formatted(text);

        try {
            LanguageDetection parsed = llm.structured("language_detect_translate", prompt, LanguageDetection.class);
            return new DetectionResult(
                    parsed.languageName() != null ? parsed.languageName() : "unknown",
                    parsed.languageCode() != null ? parsed.languageCode() : "unknown",
                    parsed.translatedText() != null ? parsed.translatedText() : text
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
            return llm.text("language_translate_reverse", prompt);
        } catch (Exception e) {
            log.warn("Reverse translation failed, returning English text: {}", e.getMessage());
            return englishText;
        }
    }
}
