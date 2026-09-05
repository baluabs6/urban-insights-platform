package com.urban.ai.service;

import com.urban.ai.dto.InsightRequest;
import com.urban.ai.dto.InsightResponse;
import com.urban.ai.rag.UrbanDataRetriever;
import dev.langchain4j.model.chat.ChatLanguageModel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * The RAG "chain" (LangChain terminology): Retrieve -> Augment -> Generate.
 *
 *  Retrieve: pull + index the freshest traffic/complaint data for the zone,
 *            then vector-search for the segments most relevant to the question.
 *  Augment:  stuff those segments into the LLM prompt as grounded context.
 *  Generate: ask the chat model to answer using ONLY that context, so answers
 *            about "today's" city conditions are accurate instead of hallucinated.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RagInsightService {

    private final UrbanDataRetriever retriever;
    private final ChatLanguageModel chatLanguageModel;

    private static final String SYSTEM_PROMPT = """
            You are an assistant for a city operations team analyzing real-time urban data
            (traffic/sensor readings and citizen complaints). Answer ONLY using the CONTEXT
            provided. If the context does not contain enough information, say so plainly
            instead of guessing. Be concise and cite which piece of context you used.
            """;

    public InsightResponse answer(InsightRequest request) {
        // 1) Retrieve: refresh the index with live data for this zone, then search.
        retriever.indexZone(request.getZone());
        List<String> context = retriever.retrieveContext(request.getQuestion());

        // 2) Augment: build a grounded prompt.
        String contextBlock = context.isEmpty()
                ? "(no relevant live data was found for this zone)"
                : String.join("\n- ", context);

        String prompt = """
                %s

                CONTEXT:
                - %s

                QUESTION: %s

                ANSWER:
                """.formatted(SYSTEM_PROMPT, contextBlock, request.getQuestion());

        // 3) Generate.
        String answer;
        try {
            answer = chatLanguageModel.generate(prompt);
        } catch (Exception e) {
            log.error("LLM call failed, falling back to context-only summary", e);
            answer = "AI model unavailable right now. Here is the raw retrieved context:\n- " + contextBlock;
        }

        return InsightResponse.builder()
                .zone(request.getZone())
                .question(request.getQuestion())
                .answer(answer)
                .retrievedContext(context)
                .build();
    }
}
