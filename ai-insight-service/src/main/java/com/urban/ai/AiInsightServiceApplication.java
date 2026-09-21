package com.urban.ai;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class AiInsightServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(AiInsightServiceApplication.class, args);
    }
}
