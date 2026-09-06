package com.urban.ai.service;

import com.urban.ai.dto.InsightRequest;
import com.urban.ai.dto.InsightResponse;
import com.urban.ai.rag.FaithfulnessChecker;
import com.urban.ai.rag.FaithfulnessChecker.FaithfulnessResult;
import com.urban.ai.rag.QueryRewriteService;
import com.urban.ai.rag.SemanticCacheService;
import com.urban.ai.rag.UrbanDataRetriever;
import com.urban.ai.rag.UrbanDataRetriever.RetrievedSegment;
import dev.langchain4j.model.chat.ChatLanguageModel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

/**
 * The RAG "chain" (LangChain terminology), now with several quality upgrades:
 *
 *  0. Semantic cache: skip everything below if a near-duplicate question for
 *     this zone was answered recently.
 *  1. Query rewriting: expand the question into a few targeted sub-queries.
 *  2. Retrieve: refresh the index, then hybrid-search (vector + keyword +
 *     recency) for each sub-query IN PARALLEL (each sub-query does its own
 *     embedding-API call + vector search — running them sequentially was pure
 *     added latency for zero benefit, since they're independent of each other).
 *  3. Augment: stuff the merged, de-duplicated, source-tagged segments into
 *     the prompt and ask the model to cite which source IDs it used.
 *  4. Generate: call the LLM.
 *  5. Faithfulness check: a second LLM pass scores whether the answer is
 *     actually supported by the retrieved context.
 *  6. Cache the answer for next time.
 *
 * Note: generation (4) and the faithfulness check (5) remain sequential by
 * necessity — the faithfulness check needs the generated answer as input.
 * That's still 2 unavoidable sequential LLM round-trips after retrieval,
 * on top of the query-rewrite call in step 1 — full latency isn't solved,
 * just the part that was needlessly serial.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RagInsightService {

    private final UrbanDataRetriever retriever;
    private final QueryRewriteService queryRewriteService;
    private final FaithfulnessChecker faithfulnessChecker;
    private final SemanticCacheService semanticCacheService;
    private final ChatLanguageModel chatLanguageModel;

    @org.springframework.beans.factory.annotation.Value("${rag.index-on-query-fallback:false}")
    private boolean indexOnQueryFallback;

    // Bounded, dedicated pool for parallel sub-query retrieval — deliberately NOT
    // the common ForkJoinPool, so a burst of RAG questions can't starve other
    // parallel-stream usage elsewhere in the JVM (or vice versa).
    private final ExecutorService retrievalExecutor = Executors.newFixedThreadPool(8);

    private static final String SYSTEM_PROMPT = """
            You are an assistant for a city operations team analyzing real-time urban data
            (traffic/sensor readings and citizen complaints). Answer ONLY using the CONTEXT
            provided. Each context item has a [source:ID] tag — reference the relevant
            source IDs in your answer (e.g. "(source: complaint-123)"). If the context does
            not contain enough information, say so plainly instead of guessing. Be concise.
            """;

    public InsightResponse answer(InsightRequest request) {
        // 0) Semantic cache check.
        Optional<SemanticCacheService.CachedEntry> cached =
                semanticCacheService.lookup(request.getZone(), request.getQuestion());
        if (cached.isPresent()) {
            return InsightResponse.builder()
                    .zone(request.getZone())
                    .question(request.getQuestion())
                    .answer(cached.get().getAnswer())
                    .retrievedContext(List.of("(served from semantic cache; original question: \""
                            + cached.get().getQuestion() + "\")"))
                    .build();
        }

        // 1) Query rewriting.
        List<String> subQueries = queryRewriteService.expand(request.getQuestion());

        // 2) Retrieve: adaptive top-k — broader/comparative questions pull more context.
        int topK = isBroadQuestion(request.getQuestion()) ? 8 : 5;

        // Indexing is event-driven by default (Kafka listeners index on write —
        // see ComplaintCreatedListener / TrafficAnomalyListener), so this is
        // normally a no-op cost. Set rag.index-on-query-fallback=true to restore
        // the old pull-and-reindex-per-query behavior (e.g. if Kafka is down or
        // for a cold-start backfill).
        if (indexOnQueryFallback) {
            retriever.indexZone(request.getZone());
        }

        // Fire all sub-query retrievals concurrently instead of one-after-another —
        // each is an independent embedding-API call + vector search.
        List<CompletableFuture<List<RetrievedSegment>>> futures = subQueries.stream()
                .map(subQuery -> CompletableFuture.supplyAsync(
                        () -> retriever.retrieve(subQuery, request.getZone(), topK), retrievalExecutor)
                        .exceptionally(e -> {
                            log.warn("Retrieval failed for sub-query '{}': {}", subQuery, e.getMessage());
                            return List.of();
                        }))
                .toList();

        Map<String, RetrievedSegment> merged = new LinkedHashMap<>(); // dedupe by sourceId+text
        for (CompletableFuture<List<RetrievedSegment>> future : futures) {
            for (RetrievedSegment segment : future.join()) {
                merged.putIfAbsent(segment.getId() + "|" + segment.getText(), segment);
            }
        }
        List<RetrievedSegment> finalSegments = merged.values().stream()
                .sorted(Comparator.comparingDouble(RetrievedSegment::getCombinedScore).reversed())
                .limit(topK)
                .collect(Collectors.toList());

        // 3) Augment: build a grounded, citeable prompt.
        String contextBlock = finalSegments.isEmpty()
                ? "(no relevant live data was found for this zone)"
                : finalSegments.stream()
                    .map(s -> String.format("[source:%s, type:%s] %s", s.getId(), s.getType(), s.getText()))
                    .collect(Collectors.joining("\n- ", "- ", ""));

        String prompt = """
                %s

                CONTEXT:
                %s

                QUESTION: %s

                ANSWER:
                """.formatted(SYSTEM_PROMPT, contextBlock, request.getQuestion());

        // 4) Generate.
        String answer;
        try {
            answer = chatLanguageModel.generate(prompt);
        } catch (Exception e) {
            log.error("LLM call failed, falling back to context-only summary", e);
            answer = "AI model unavailable right now. Here is the raw retrieved context:\n" + contextBlock;
        }

        // 5) Faithfulness check.
        List<String> contextTexts = finalSegments.stream().map(RetrievedSegment::getText).toList();
        FaithfulnessResult faithfulness = faithfulnessChecker.check(answer, contextTexts);

        // 6) Cache for next time (only cache reasonably faithful answers).
        if (faithfulness.getScore() >= 0.6) {
            semanticCacheService.store(request.getZone(), request.getQuestion(), answer);
        }

        return InsightResponse.builder()
                .zone(request.getZone())
                .question(request.getQuestion())
                .answer(answer)
                .retrievedContext(contextTexts)
                .sources(finalSegments.stream().map(s -> Map.of(
                        "id", s.getId(),
                        "type", s.getType(),
                        "vectorScore", String.format("%.3f", s.getVectorScore()),
                        "keywordScore", String.format("%.3f", s.getKeywordScore()),
                        "recencyScore", String.format("%.3f", s.getRecencyScore())
                )).collect(Collectors.toList()))
                .faithfulnessScore(faithfulness.getScore())
                .faithfulnessRationale(faithfulness.getRationale())
                .build();
    }

    private boolean isBroadQuestion(String question) {
        String q = question.toLowerCase();
        return q.contains("compare") || q.contains("overall") || q.contains("all zones")
                || q.contains("why") || q.contains("summary") || q.contains("everything");
    }
}
