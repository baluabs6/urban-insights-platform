package com.urban.ai.memory;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Redis-backed {@link ChatMemoryRepository} so multi-turn conversations survive restarts and are shared
 * across replicas. Each conversation is one key holding a JSON list of {role, text} pairs, with a TTL that is
 * refreshed on every write — abandoned conversations expire instead of growing memory without bound.
 *
 * <p>Only USER and ASSISTANT turns are stored; the system prompt and tool traffic are re-created per request.
 */
@Component
@Slf4j
public class RedisChatMemoryRepository implements ChatMemoryRepository {

    private static final String KEY_PREFIX = "chat-memory:";
    private static final TypeReference<List<StoredMessage>> STORED_LIST = new TypeReference<>() {};

    public record StoredMessage(String role, String text) {}

    private final RedisTemplate<String, Object> redisTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Duration ttl;

    public RedisChatMemoryRepository(RedisTemplate<String, Object> redisTemplate,
                                     @Value("${ai.chat-memory.ttl-hours:24}") long ttlHours) {
        this.redisTemplate = redisTemplate;
        this.ttl = Duration.ofHours(ttlHours);
    }

    @Override
    public List<String> findConversationIds() {
        List<String> ids = new ArrayList<>();
        ScanOptions options = ScanOptions.scanOptions().match(KEY_PREFIX + "*").count(100).build();
        try (Cursor<String> cursor = redisTemplate.scan(options)) {
            while (cursor.hasNext()) {
                ids.add(cursor.next().substring(KEY_PREFIX.length()));
            }
        } catch (Exception e) {
            log.warn("Failed to list chat-memory conversations: {}", e.getMessage());
        }
        return ids;
    }

    @Override
    public List<Message> findByConversationId(String conversationId) {
        try {
            Object raw = redisTemplate.opsForValue().get(KEY_PREFIX + conversationId);
            if (raw == null) return new ArrayList<>();
            String json = raw instanceof String s ? s : objectMapper.writeValueAsString(raw);
            List<Message> messages = new ArrayList<>();
            for (StoredMessage stored : objectMapper.readValue(json, STORED_LIST)) {
                if (MessageType.USER.name().equals(stored.role())) {
                    messages.add(new UserMessage(stored.text()));
                } else if (MessageType.ASSISTANT.name().equals(stored.role())) {
                    messages.add(new AssistantMessage(stored.text()));
                }
            }
            return messages;
        } catch (Exception e) {
            log.warn("Failed to read chat memory for conversation {}: {}", conversationId, e.getMessage());
            return new ArrayList<>();
        }
    }

    @Override
    public void saveAll(String conversationId, List<Message> messages) {
        try {
            List<StoredMessage> stored = messages.stream()
                    .filter(m -> m.getMessageType() == MessageType.USER || m.getMessageType() == MessageType.ASSISTANT)
                    .map(m -> new StoredMessage(m.getMessageType().name(), m.getText()))
                    .toList();
            redisTemplate.opsForValue().set(KEY_PREFIX + conversationId, objectMapper.writeValueAsString(stored), ttl);
        } catch (Exception e) {
            log.warn("Failed to write chat memory for conversation {}: {}", conversationId, e.getMessage());
        }
    }

    @Override
    public void deleteByConversationId(String conversationId) {
        redisTemplate.delete(KEY_PREFIX + conversationId);
    }
}
