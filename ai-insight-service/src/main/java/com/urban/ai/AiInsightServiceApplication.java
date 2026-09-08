package com.urban.ai;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * AI / GenAI Insight Service
 * --------------------------
 * The "brain" of the platform. Combines:
 *   - AI module:  statistical/ML scoring already happening upstream (traffic-service anomaly z-scores)
 *   - GenAI/LLM:  a chat model (OpenAI-compatible, or any local model via LangChain4j) that turns
 *                 raw numbers into a human explanation
 *   - RAG:        retrieves the freshest traffic + complaint data from the other microservices,
 *                 embeds/stores it, and grounds the LLM's answer in that retrieved context
 *                 instead of letting it hallucinate about "today's AQI in Whitefield"
 *   - LangChain(4j): orchestrates the retrieve -> augment -> generate pipeline
 */
@SpringBootApplication
@EnableScheduling
public class AiInsightServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(AiInsightServiceApplication.class, args);
    }
}
