package com.urban.ai.genai;

import com.urban.ai.dto.GenAiDtos.ClassifyRequest;
import com.urban.ai.dto.GenAiDtos.VoiceComplaintRequest;
import com.urban.ai.dto.GenAiDtos.VoiceComplaintResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.Base64;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class VoiceTranscriptionService {

    private final WebClient.Builder webClientBuilder;
    private final ComplaintClassificationService classificationService;
    private final LanguageSupportService languageSupportService;
    private final com.urban.ai.metrics.LlmCallMetrics llmCallMetrics;

    @Value("${ai.transcription.url:https://api.openai.com/v1/audio/transcriptions}")
    private String transcriptionUrl;

    @Value("${ai.transcription.api-key:${ai.openai.api-key:demo-key}}")
    private String transcriptionApiKey;

    @Value("${ai.transcription.model:whisper-1}")
    private String transcriptionModel;

    public VoiceComplaintResponse transcribeAndClassify(VoiceComplaintRequest request) {
        String transcript = transcribe(request.getAudioBase64(), request.getAudioFormat());

        LanguageSupportService.DetectionResult detected =
                languageSupportService.detectAndTranslateToEnglish(transcript);

        ClassifyRequest classifyRequest = new ClassifyRequest();
        classifyRequest.setDescription(transcript);
        classifyRequest.setZone(request.getZone());

        return VoiceComplaintResponse.builder()
                .transcript(transcript)
                .detectedLanguage(detected.languageName())
                .classification(classificationService.classify(classifyRequest))
                .build();
    }

    private String transcribe(String audioBase64, String audioFormat) {
        try {
            byte[] audioBytes = Base64.getDecoder().decode(audioBase64);

            org.springframework.util.MultiValueMap<String, Object> form = new org.springframework.util.LinkedMultiValueMap<>();
            form.add("file", new org.springframework.core.io.ByteArrayResource(audioBytes) {
                @Override
                public String getFilename() {
                    return "complaint-voice-note." + audioFormat;
                }
            });
            form.add("model", transcriptionModel);

            WebClient client = webClientBuilder.build();

            Map<?, ?> response = llmCallMetrics.time("voice_transcription", () -> client.post()
                    .uri(transcriptionUrl)
                    .header("Authorization", "Bearer " + transcriptionApiKey)
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .bodyValue(form)
                    .retrieve()
                    .bodyToMono(Map.class)
                    .timeout(Duration.ofSeconds(30))
                    .block());

            Object text = response != null ? response.get("text") : null;
            if (text == null) {
                throw new IllegalStateException("Transcription backend returned no text field");
            }
            return String.valueOf(text);

        } catch (Exception e) {
            log.warn("Voice transcription failed: {}", e.getMessage());
            throw new IllegalStateException("Voice transcription unavailable: " + e.getMessage(), e);
        }
    }
}
