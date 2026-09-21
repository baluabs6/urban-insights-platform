package com.urban.complaint.security;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.Arrays;

@Component
@Slf4j
public class SecretsStartupCheck implements ApplicationRunner {

    @Value("${security.enabled:true}")
    private boolean securityEnabled;

    @Value("${security.api-key:change-me-in-prod}")
    private String apiKey;

    @Value("${security.admin-api-key:change-me-admin-in-prod}")
    private String adminApiKey;

    private final Environment environment;

    public SecretsStartupCheck(Environment environment) {
        this.environment = environment;
    }

    private static final String DEFAULT_API_KEY = "change-me-in-prod";
    private static final String DEFAULT_ADMIN_KEY = "change-me-admin-in-prod";

    @Override
    public void run(ApplicationArguments args) {
        if (!securityEnabled) {
            log.warn("SECURITY IS DISABLED (security.enabled=false) — every endpoint is unauthenticated. " +
                    "Never run this outside local development.");
            return;
        }

        boolean usingDefaultKeys = DEFAULT_API_KEY.equals(apiKey) || DEFAULT_ADMIN_KEY.equals(adminApiKey);
        if (!usingDefaultKeys) {
            return;
        }

        boolean isProdProfile = Arrays.asList(environment.getActiveProfiles()).contains("prod");
        if (isProdProfile) {
            throw new IllegalStateException(
                    "Refusing to start with 'prod' profile active and default API key(s) unchanged. " +
                    "Set SECURITY_API_KEY and SECURITY_ADMIN_API_KEY to real secrets before deploying.");
        }

        log.warn("################################################################");
        log.warn("# SECURITY WARNING: running with the DEFAULT API key(s).       #");
        log.warn("# Fine for local dev — never deploy this configuration as-is.  #");
        log.warn("# Set SECURITY_API_KEY / SECURITY_ADMIN_API_KEY to override.   #");
        log.warn("################################################################");
    }
}
