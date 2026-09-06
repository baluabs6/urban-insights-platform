package com.urban.ai.genai;

import com.urban.ai.dto.GenAiDtos.DuplicateCheckRequest;
import com.urban.ai.dto.GenAiDtos.DuplicateCheckResponse;
import com.urban.ai.rag.UrbanDataRetriever;
import com.urban.ai.rag.UrbanDataRetriever.RetrievedSegment;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Duplicate detection now queries the persistent pgvector index that
 * ComplaintCreatedListener already maintains via index-on-write, instead of
 * re-fetching every open complaint in the zone over REST and re-embedding
 * each one from scratch on every single check (the previous version did up
 * to N embedding-API calls per duplicate check, for a check that runs on
 * every complaint submission). This is now one embedding call (the query
 * itself) plus a vector search — the per-complaint embeddings already exist.
 *
 * Trade-off: this only finds duplicates among complaints that have already
 * been indexed. A complaint indexed moments ago by ComplaintCreatedListener
 * (which indexes before running this check) is visible; anything indexed via
 * the old REST-pull path (index-on-query-fallback) is also visible. Nothing
 * is lost versus the old approach, which also depended on complaint-service
 * already having persisted the record.
 */
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

        // Duplicate judgment uses pure vector similarity (vectorScore), not the
        // hybrid combinedScore RAG uses elsewhere — keyword/recency weighting
        // would distort "is this the same underlying issue?" in either direction.
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
