package com.urban.ai.rag;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.chat.ChatLanguageModel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Rewrites a (possibly vague) user question into 2-3 more specific sub-queries
 * before retrieval, e.g. "what's going on there?" -> ["current traffic conditions",
 * "recent sensor anomalies", "open citizen complaints"]. Each variant is embedded
 * and searched separately in RagInsightService; results are merged and re-ranked.
 * Falls back to the original question if the LLM call fails.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class QueryRewriteService {

    private final ChatLanguageModel chatLanguageModel;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public List<String> expand(String question) {
        String prompt = """
                Rewrite the following question about city conditions into 2-3 short, specific
                search queries that would help retrieve relevant sensor/traffic/complaint data.
                Respond with STRICT JSON only: {"queries": ["...", "...", "..."]}

                QUESTION: "%s"
                """.formatted(question);

        try {
            String raw = chatLanguageModel.generate(prompt);
            String json = stripFences(raw);
            @SuppressWarnings("unchecked")
            var parsed = objectMapper.readValue(json, java.util.Map.class);
            @SuppressWarnings("unchecked")
            List<String> queries = (List<String>) parsed.get("queries");
            if (queries == null || queries.isEmpty()) {
                return List.of(question);
            }
            // Always include the original question too — rewriting augments, doesn't replace.
            List<String> all = new java.util.ArrayList<>(queries);
            all.add(question);
            return all.stream().distinct().toList();
        } catch (Exception e) {
            log.warn("Query rewriting failed, using original question only: {}", e.getMessage());
            return List.of(question);
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
