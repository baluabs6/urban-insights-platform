package com.urban.traffic.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.serializer.JsonSerializer;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.ProducerFactory;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.config.TopicBuilder;

import java.util.HashMap;
import java.util.Map;

/**
 * This is the "real-time" backbone: sensor readings are published here instead
 * of being processed synchronously inside the HTTP request thread. A separate
 * consumer (SensorReadingConsumer) does the actual DB write + anomaly scoring
 * on its own thread pool, so the ingest API can absorb bursts from thousands of
 * concurrent devices without blocking on Postgres.
 */
@Configuration
public class KafkaProducerConfig {

    public static final String TOPIC_SENSOR_READINGS = "traffic.readings";
    public static final String TOPIC_ANOMALIES = "traffic.anomalies";

    @Value("${spring.kafka.bootstrap-servers:localhost:9092}")
    private String bootstrapServers;

    @Bean
    public ProducerFactory<String, Object> producerFactory() {
        Map<String, Object> config = new HashMap<>();
        config.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        config.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        config.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class);
        // Favor throughput slightly over strict per-message latency — fine for telemetry.
        config.put(ProducerConfig.LINGER_MS_CONFIG, 5);
        config.put(ProducerConfig.ACKS_CONFIG, "1");
        return new DefaultKafkaProducerFactory<>(config);
    }

    @Bean
    public KafkaTemplate<String, Object> kafkaTemplate(ProducerFactory<String, Object> producerFactory) {
        return new KafkaTemplate<>(producerFactory);
    }

    @Bean
    public NewTopic sensorReadingsTopic() {
        return TopicBuilder.name(TOPIC_SENSOR_READINGS).partitions(6).replicas(1).build();
    }

    @Bean
    public NewTopic anomaliesTopic() {
        return TopicBuilder.name(TOPIC_ANOMALIES).partitions(3).replicas(1).build();
    }
}
