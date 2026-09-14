package com.urban.traffic.config;

import com.urban.traffic.dto.SensorReadingRequest;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.serializer.ErrorHandlingDeserializer;
import org.springframework.kafka.support.serializer.JsonDeserializer;
import org.springframework.util.backoff.FixedBackOff;

import java.util.HashMap;
import java.util.Map;

@Configuration
public class KafkaConsumerConfig {

    @Value("${spring.kafka.bootstrap-servers:localhost:9092}")
    private String bootstrapServers;

    public static final String DLT_SUFFIX = ".DLT";

    @Bean
    public ConsumerFactory<String, SensorReadingRequest> sensorReadingConsumerFactory() {
        Map<String, Object> config = new HashMap<>();
        config.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        config.put(ConsumerConfig.GROUP_ID_CONFIG, "traffic-service-ingestion");
        config.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, ErrorHandlingDeserializer.class);
        config.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ErrorHandlingDeserializer.class);
        config.put(ErrorHandlingDeserializer.KEY_DESERIALIZER_CLASS, StringDeserializer.class);
        config.put(ErrorHandlingDeserializer.VALUE_DESERIALIZER_CLASS, JsonDeserializer.class);
        config.put(JsonDeserializer.TRUSTED_PACKAGES, "com.urban.traffic.dto");
        config.put(JsonDeserializer.VALUE_DEFAULT_TYPE, SensorReadingRequest.class.getName());
        config.put(JsonDeserializer.USE_TYPE_INFO_HEADERS, false);
        // Auto-commit is fine here: a dropped reading is acceptable telemetry loss;
        // it must NOT block the whole partition on a poison message.
        config.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, true);
        return new DefaultKafkaConsumerFactory<>(config);
    }

    /**
     * Poison messages (bad deserialization, or a listener that keeps throwing) used
     * to be silently retried-forever-then-dropped by the default Spring Kafka error
     * handling — no record of what was lost. This publishes to "<topic>.DLT" after a
     * couple of quick retries, so a bad message is inspectable instead of vanishing.
     * The in-method try/catch in SensorReadingConsumer still handles the common case
     * (one malformed reading) without ever reaching this path; this is the backstop
     * for deserialization failures and anything that slips past that catch.
     */
    @Bean
    public DefaultErrorHandler kafkaErrorHandler(KafkaTemplate<String, Object> kafkaTemplate) {
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(kafkaTemplate,
                (record, ex) -> new org.apache.kafka.common.TopicPartition(record.topic() + DLT_SUFFIX, record.partition()));
        // 3 attempts total, 500ms apart, before giving up and publishing to the DLT.
        DefaultErrorHandler handler = new DefaultErrorHandler(recoverer, new FixedBackOff(500L, 2L));
        handler.addNotRetryableExceptions(org.springframework.kafka.support.serializer.DeserializationException.class);
        return handler;
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, SensorReadingRequest> kafkaListenerContainerFactory(
            ConsumerFactory<String, SensorReadingRequest> sensorReadingConsumerFactory,
            DefaultErrorHandler kafkaErrorHandler) {
        ConcurrentKafkaListenerContainerFactory<String, SensorReadingRequest> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(sensorReadingConsumerFactory);
        // Multiple threads consuming in parallel across the topic's 6 partitions —
        // this is what actually lets the pipeline absorb a real sensor fleet's throughput.
        factory.setConcurrency(3);
        factory.setCommonErrorHandler(kafkaErrorHandler);
        return factory;
    }
}
