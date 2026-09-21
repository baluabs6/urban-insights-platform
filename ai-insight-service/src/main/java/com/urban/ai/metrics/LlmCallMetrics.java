package com.urban.ai.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.function.Supplier;

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

    public void timeRunnable(String callSite, Runnable call) {
        time(callSite, () -> {
            call.run();
            return null;
        });
    }
}
