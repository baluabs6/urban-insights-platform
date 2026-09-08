package com.urban.complaint;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Citizen Complaint Service
 * -------------------------
 * Civic complaints (potholes, garbage overflow, broken streetlights, water leakage,
 * illegal construction, etc.) are semi-structured and highly variable in shape —
 * photos, free-text descriptions, geo-coordinates, department metadata — which is
 * exactly the kind of data MongoDB's flexible document model handles well, versus
 * forcing it into rigid relational tables.
 */
@SpringBootApplication
public class ComplaintServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(ComplaintServiceApplication.class, args);
    }
}
