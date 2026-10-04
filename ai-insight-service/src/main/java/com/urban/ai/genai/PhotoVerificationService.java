package com.urban.ai.genai;

import com.urban.ai.dto.GenAiDtos.PhotoVerificationRequest;
import com.urban.ai.dto.GenAiDtos.PhotoVerificationResponse;
import com.urban.ai.llm.LlmClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.content.Media;
import org.springframework.stereotype.Service;
import org.springframework.util.MimeType;
import org.springframework.util.MimeTypeUtils;

import java.net.URI;
import java.util.List;
import java.util.Locale;

/** Multimodal check: sends the complaint photo URLs to a vision-capable model via Spring AI {@link Media}. */
@Service
@RequiredArgsConstructor
@Slf4j
public class PhotoVerificationService {

    private final LlmClient llm;

    private static final int MAX_PHOTOS_CHECKED = 3;

    /** Structured-output target: Spring AI derives the JSON schema from this record. */
    public record PhotoVerdict(Boolean verified, Double confidence, String note) {}

    public PhotoVerificationResponse verify(PhotoVerificationRequest request) {
        List<String> photos = request.getPhotoUrls().stream().limit(MAX_PHOTOS_CHECKED).toList();

        String prompt = """
                A citizen filed a civic complaint categorized as "%s". Look at the attached
                photo(s) and judge whether they plausibly show evidence of that category
                (e.g. POTHOLE -> visible road damage, GARBAGE -> uncollected waste, STREETLIGHT
                -> a streetlight/pole, WATER_LEAKAGE -> standing water or a pipe/leak, ENCROACHMENT
                -> illegal structure/obstruction, NOISE -> no reliable visual evidence is possible,
                treat as inconclusive rather than false).
                "verified" is a boolean; "confidence" is a number from 0.0 to 1.0; "note" is one short sentence.
                """.formatted(request.getCategory());

        try {
            List<Media> media = photos.stream()
                    .map(url -> new Media(mimeTypeFor(url), URI.create(url)))
                    .toList();

            PhotoVerdict parsed = llm.structuredWithMedia("photo_verification", prompt, media, PhotoVerdict.class);

            boolean verified = Boolean.TRUE.equals(parsed.verified());
            double confidence = parsed.confidence() != null ? parsed.confidence() : 0.5;

            return PhotoVerificationResponse.builder()
                    .complaintId(request.getComplaintId())
                    .verified(verified)
                    .confidence(clamp(confidence))
                    .note(parsed.note() != null ? parsed.note() : "")
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

    /** Best-effort MIME type from the URL's extension (query string ignored); defaults to JPEG. */
    private MimeType mimeTypeFor(String url) {
        String path = url.toLowerCase(Locale.ROOT);
        int q = path.indexOf('?');
        if (q >= 0) path = path.substring(0, q);
        if (path.endsWith(".png")) return MimeTypeUtils.IMAGE_PNG;
        if (path.endsWith(".gif")) return MimeTypeUtils.IMAGE_GIF;
        if (path.endsWith(".webp")) return MimeType.valueOf("image/webp");
        return MimeTypeUtils.IMAGE_JPEG;
    }

    private double clamp(double v) {
        return Math.max(0.0, Math.min(1.0, v));
    }
}
