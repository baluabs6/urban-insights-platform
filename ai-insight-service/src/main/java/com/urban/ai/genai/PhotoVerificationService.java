package com.urban.ai.genai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.urban.ai.dto.GenAiDtos.PhotoVerificationRequest;
import com.urban.ai.dto.GenAiDtos.PhotoVerificationResponse;
import dev.langchain4j.data.message.ImageContent;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.output.Response;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class PhotoVerificationService {

    private final ChatLanguageModel chatLanguageModel;
    private final com.urban.ai.metrics.LlmCallMetrics llmCallMetrics;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final int MAX_PHOTOS_CHECKED = 3;

    public PhotoVerificationResponse verify(PhotoVerificationRequest request) {
        List<String> photos = request.getPhotoUrls().stream().limit(MAX_PHOTOS_CHECKED).toList();

        String prompt = """
                A citizen filed a civic complaint categorized as "%s". Look at the attached
                photo(s) and judge whether they plausibly show evidence of that category
                (e.g. POTHOLE -> visible road damage, GARBAGE -> uncollected waste, STREETLIGHT
                -> a streetlight/pole, WATER_LEAKAGE -> standing water or a pipe/leak, ENCROACHMENT
                -> illegal structure/obstruction, NOISE -> no reliable visual evidence is possible,
                treat as inconclusive rather than false).
                Respond with STRICT JSON only, no markdown fences, exactly this shape:
                {"verified": <true|false>, "confidence": <number 0.0-1.0>, "note": "<one short sentence>"}
                """.formatted(request.getCategory());

        try {
            List<dev.langchain4j.data.message.Content> contents = new java.util.ArrayList<>();
            contents.add(TextContent.from(prompt));
            for (String url : photos) {
                contents.add(ImageContent.from(url));
            }
            UserMessage userMessage = UserMessage.from(contents);

            Response<dev.langchain4j.data.message.AiMessage> response = llmCallMetrics.time(
                    "photo_verification", () -> chatLanguageModel.generate(userMessage));

            String json = extractJson(response.content().text());
            Map<String, Object> parsed = objectMapper.readValue(json, Map.class);

            boolean verified = Boolean.TRUE.equals(parsed.get("verified"));
            double confidence = parsed.get("confidence") instanceof Number n ? n.doubleValue() : 0.5;

            return PhotoVerificationResponse.builder()
                    .complaintId(request.getComplaintId())
                    .verified(verified)
                    .confidence(clamp(confidence))
                    .note(String.valueOf(parsed.getOrDefault("note", "")))
                    .build();

        } catch (Exception e) {
            log.warn("Photo verification failed for complaint {} (model may not support image input): {}",
                    request.getComplaintId(), e.getMessage());
            return PhotoVerificationResponse.builder()
                    .complaintId(request.getComplaintId())
                    .verified(false)
                    .confidence(0.0)
                    .note("Verification unavailable — review manually.")
                    .build();
        }
    }

    private double clamp(double v) {
        return Math.max(0.0, Math.min(1.0, v));
    }

    private String extractJson(String raw) {
        String trimmed = raw.trim();
        if (trimmed.startsWith("```")) {
            trimmed = trimmed.replaceAll("^```(json)?", "").replaceAll("```$", "").trim();
        }
        return trimmed;
    }
}
