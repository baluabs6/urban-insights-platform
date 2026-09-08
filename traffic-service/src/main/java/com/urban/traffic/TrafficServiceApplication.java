package com.urban.traffic;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Traffic & Sensor Service
 * ------------------------
 * Ingests real-time traffic / pollution / noise sensor data from city IoT devices,
 * persists structured readings in PostgreSQL, caches "latest reading per sensor"
 * in Redis for sub-millisecond dashboard reads, and runs a lightweight statistical
 * anomaly detector (z-score) as a first-line AI module before handing off
 * deeper reasoning to the ai-insight-service (LLM/RAG).
 */
@SpringBootApplication
@EnableCaching
@EnableScheduling
public class TrafficServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(TrafficServiceApplication.class, args);
    }
}
