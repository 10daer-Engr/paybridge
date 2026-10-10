package com.academy.paybridge.customer;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A browser sends an OPTIONS "preflight" with no API key before any call that carries custom headers.
 * Spring Security would answer that with 401 unless CORS is wired into the security chain.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class CorsPreflightTest {

    @Value("${local.server.port}")
    int port;

    private final HttpClient http = HttpClient.newHttpClient();

    @Test
    void preflightFromAnAllowedSiteSucceedsWithoutAnApiKey() throws Exception {
        HttpResponse<String> response = preflight("http://localhost:3000", "/api/v1/transfers/external");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Access-Control-Allow-Origin")).hasValue("http://localhost:3000");
        String allowed = response.headers().firstValue("Access-Control-Allow-Headers").orElse("").toLowerCase();
        assertThat(allowed).contains("x-api-key").contains("idempotency-key").contains("content-type");
    }

    @Test
    void preflightFromAnUnknownSiteIsRefused() throws Exception {
        HttpResponse<String> response = preflight("https://evil.example.com", "/api/v1/transfers/external");

        assertThat(response.statusCode()).isEqualTo(403);
    }

    @Test
    void ordinaryResponsesToAnAllowedSiteCarryTheCorsHeader() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/actuator/health"))
                .header("Origin", "http://localhost:3000")
                .GET().build();

        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.headers().firstValue("Access-Control-Allow-Origin")).hasValue("http://localhost:3000");
    }

    private HttpResponse<String> preflight(String origin, String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Origin", origin)
                .header("Access-Control-Request-Method", "POST")
                .header("Access-Control-Request-Headers", "content-type,x-api-key,idempotency-key")
                .method("OPTIONS", HttpRequest.BodyPublishers.noBody())
                .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
