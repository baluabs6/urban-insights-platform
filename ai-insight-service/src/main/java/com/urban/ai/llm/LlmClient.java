package com.urban.ai.llm;

import com.urban.ai.metrics.LlmCallMetrics;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.content.Media;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Single entry point for LLM calls, built on Spring AI's {@link ChatClient}.
 *
 * <p>Every call is timed under the existing {@code urban.llm.call} metric (so the Grafana
 * "LLM Cost &amp; Latency" dashboard keeps working) in addition to the Micrometer observations
 * Spring AI emits on its own.
 *
 * <p>Prompts are passed as {@link Prompt}/{@code Message} objects rather than template strings on purpose:
 * our prompts embed JSON examples and citizen-supplied text containing {@code { }} characters, which
 * must never be interpreted as template placeholders.
 *
 * <p>Structured output uses Spring AI's {@link BeanOutputConverter}: it appends a JSON-schema format
 * instruction derived from the target type and parses (and de-fences) the reply into that type.
 */
@Component
@RequiredArgsConstructor
public class LlmClient {

    private final ChatClient chatClient;
    private final LlmCallMetrics llmCallMetrics;

    public String text(String callSite, String userPrompt) {
        return call(callSite, new Prompt(new UserMessage(userPrompt)));
    }

    public String text(String callSite, String systemPrompt, String userPrompt) {
        return call(callSite, new Prompt(List.of(new SystemMessage(systemPrompt), new UserMessage(userPrompt))));
    }

    /** Calls the model and maps the reply to {@code type} (a record or bean). Throws if the reply can't be parsed. */
    public <T> T structured(String callSite, String userPrompt, Class<T> type) {
        BeanOutputConverter<T> converter = new BeanOutputConverter<>(type);
        String raw = text(callSite, userPrompt + "\n\n" + converter.getFormat());
        return converter.convert(raw);
    }

    /** Multimodal variant: attaches images/media to the user message. */
    public <T> T structuredWithMedia(String callSite, String userPrompt, List<Media> media, Class<T> type) {
        BeanOutputConverter<T> converter = new BeanOutputConverter<>(type);
        UserMessage message = UserMessage.builder()
                .text(userPrompt + "\n\n" + converter.getFormat())
                .media(media)
                .build();
        String raw = call(callSite, new Prompt(message));
        return converter.convert(raw);
    }

    private String call(String callSite, Prompt prompt) {
        String content = llmCallMetrics.time(callSite, () -> chatClient.prompt(prompt).call().content());
        if (content == null || content.isBlank()) {
            throw new IllegalStateException("LLM returned an empty response for call site '" + callSite + "'");
        }
        return content;
    }
}
