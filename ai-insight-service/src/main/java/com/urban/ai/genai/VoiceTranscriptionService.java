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

/**
 * Voice-complaint intake AI module: lets a citizen file (or ask about) a
 * complaint via a voice note instead of typing, pairing naturally with
 * LanguageSupportService (regional-language voice notes work the same way
 * text does — transcribe, then the existing translate-to-English step
 * handles the rest).
 *
 * Transcription is delegated to a configurable Whisper-compatible HTTP
 * endpoint (ai.transcription.url) rather than bundling a speech model into
 * this service — the same "swap the backend, keep the code" pattern
 * LangChainConfig already uses for the chat/embedding models. Defaults to
 * OpenAI's audio transcription endpoint; point it at a self-hosted
 * faster-whisper/vLLM-audio server for local/offline use.
 */
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

        // Reuse the existing language pipeline — regional-language voice notes
        // get translated to English the same way regional-language TEXT does.
        LanguageSupportService.DetectionResult detected =
                languageSupportService.detectAndTranslateToEnglish(transcript);

        ClassifyRequest classifyRequest = new ClassifyRequest();
        classifyRequest.setDescription(transcript); // classify() re-detects/translates internally
        classifyRequest.setZone(request.getZone());

        return VoiceComplaintResponse.builder()
                .transcript(transcript)
                .detectedLanguage(detected.languageName())
                .classification(classificationService.classify(classifyRequest))
                .build();
    }

    /**
     * Best-effort multipart call to the transcription backend. Audio arrives
     * base64-encoded in the request body (simplest path for a JSON API; a
     * multipart file-upload endpoint would be the production choice for large
     * audio files, but that's a bigger surface change than this module needs
     * to demonstrate the capability).
     */
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
