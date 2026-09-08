package com.urban.ai.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.function.Supplier;

/**
 * The actual bottleneck in this system is LLM/embedding call latency and
 * cost, not the Spring services around them — generic HTTP request metrics
 * (which Spring Boot gives you for free) don't show that. This wraps each
 * LLM/embedding call site with:
 *   - a Timer per (callSite) recording latency distribution
 *   - a Counter per (callSite, outcome=success|failure) for error-rate tracking
 *
 * Exposed at /actuator/prometheus as urban_llm_call_seconds{call_site=...}
 * and urban_llm_call_total{call_site=...,outcome=...} once Prometheus is
 * scraping (see infra/prometheus/prometheus.yml).
 */
@Component
@RequiredArgsConstructor
public class LlmCallMetrics {

    private final MeterRegistry meterRegistry;

    public <T> T time(String callSite, Supplier<T> call) {
        Timer.Sample sample = Timer.start(meterRegistry);
        boolean success = false;
        try {
            T result = call.get();
            success = true;
            return result;
        } finally {
            sample.stop(Timer.builder("urban.llm.call")
                    .description("Latency of LLM/embedding calls by call site")
                    .tag("call_site", callSite)
                    .register(meterRegistry));
            meterRegistry.counter("urban.llm.call.outcome",
                    "call_site", callSite,
                    "outcome", success ? "success" : "failure"
            ).increment();
        }
    }

    /** Void-returning variant for calls made purely for a side effect. */
    public void timeRunnable(String callSite, Runnable call) {
        time(callSite, () -> {
            call.run();
            return null;
        });
    }
}
