package com.urban.ai.genai;

import com.urban.ai.dto.GenAiDtos.DuplicateCheckRequest;
import com.urban.ai.dto.GenAiDtos.DuplicateCheckResponse;
import com.urban.ai.rag.UrbanDataRetriever;
import com.urban.ai.rag.UrbanDataRetriever.RetrievedSegment;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class DuplicateDetectionService {

    private final UrbanDataRetriever retriever;

    private static final double DUPLICATE_THRESHOLD = 0.85;
    private static final int CANDIDATES_TO_CONSIDER = 10;

    public DuplicateCheckResponse check(DuplicateCheckRequest request) {
        List<RetrievedSegment> candidates = retriever.retrieve(
                request.getDescription(),
                request.getZone(),
                "complaint",
                request.getExcludeComplaintId(),
                CANDIDATES_TO_CONSIDER
        );

        List<String> similar = candidates.stream()
                .filter(c -> c.getVectorScore() >= DUPLICATE_THRESHOLD)
                .map(RetrievedSegment::getText)
                .toList();

        double maxScore = candidates.stream()
                .mapToDouble(RetrievedSegment::getVectorScore)
                .max()
                .orElse(0.0);

        return DuplicateCheckResponse.builder()
                .duplicate(!similar.isEmpty())
                .maxSimilarityScore(maxScore)
                .similarComplaints(similar)
                .build();
    }
}
