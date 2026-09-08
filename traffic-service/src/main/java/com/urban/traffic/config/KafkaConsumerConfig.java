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
import org.springframework.kafka.support.serializer.ErrorHandlingDeserializer;
import org.springframework.kafka.support.serializer.JsonDeserializer;

import java.util.HashMap;
import java.util.Map;

@Configuration
public class KafkaConsumerConfig {

    @Value("${spring.kafka.bootstrap-servers:localhost:9092}")
    private String bootstrapServers;

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

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, SensorReadingRequest> kafkaListenerContainerFactory(
            ConsumerFactory<String, SensorReadingRequest> sensorReadingConsumerFactory) {
        ConcurrentKafkaListenerContainerFactory<String, SensorReadingRequest> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(sensorReadingConsumerFactory);
        // Multiple threads consuming in parallel across the topic's 6 partitions —
        // this is what actually lets the pipeline absorb a real sensor fleet's throughput.
        factory.setConcurrency(3);
        return factory;
    }
}
