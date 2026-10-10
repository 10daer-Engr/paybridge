package com.academy.paybridge.shared.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;

/**
 * Lets the PayBridge frontend (hosted on a different origin, for example Vercel) call this API
 * from the browser.
 *
 * Set PAYBRIDGE_CORS_ALLOWED_ORIGINS on the server to a comma-separated list of origins, for example
 * https://paybridge-demo.vercel.app,https://paybridge-*.vercel.app. Patterns with * are allowed, which
 * covers Vercel's per-deployment preview URLs.
 *
 * Authentication uses the X-API-Key header, not cookies, so credentials are deliberately NOT allowed.
 */
@Configuration
public class CorsConfig {

    @Bean
    CorsConfigurationSource corsConfigurationSource(
            @Value("${paybridge.cors.allowed-origins:http://localhost:3000,http://localhost:5500,http://127.0.0.1:5500}")
            String allowedOrigins) {

        List<String> origins = Arrays.stream(allowedOrigins.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();

        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOriginPatterns(origins);
        config.setAllowedMethods(List.of("GET", "POST", "OPTIONS"));
        config.setAllowedHeaders(List.of("Content-Type", "Accept", "X-API-Key", "Idempotency-Key"));
        config.setExposedHeaders(List.of("Retry-After"));   // lets the browser read the rate-limit wait time
        config.setAllowCredentials(false);
        config.setMaxAge(Duration.ofHours(1));

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}
