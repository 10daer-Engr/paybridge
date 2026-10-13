package com.academy.paybridge.partner;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class PartnerSecurityFlowTest {

    @Value("${local.server.port}")
    int port;

    private final HttpClient http = HttpClient.newHttpClient();

    private record Response(int status, String body) {
    }

    @Test
    void aPartnerCanRegisterAndSeeItself() throws Exception {
        String name = uniquePartnerName();
        Response registration = registerPartner(name);

        assertThat(registration.status()).isEqualTo(201);
        String key = field(registration.body(), "apiKey");
        assertThat(key).startsWith("pbp_");

        Response me = call("GET", "/api/v1/partner/me", key, null);
        assertThat(me.status()).isEqualTo(200);
        assertThat(me.body()).contains(name).contains("10000.00");
    }

    @Test
    void aCustomerKeyCannotUsePartnerEndpoints() throws Exception {
        String customerKey = registerCustomerKey();

        Response response = call("GET", "/api/v1/partner/me", customerKey, null);

        assertThat(response.status()).isEqualTo(403);
        assertThat(response.body()).contains("FORBIDDEN");
    }

    @Test
    void aPartnerKeyCannotUseCustomerEndpoints() throws Exception {
        String partnerKey = field(registerPartner(uniquePartnerName()).body(), "apiKey");

        assertThat(call("GET", "/api/v1/accounts", partnerKey, null).status()).isEqualTo(403);
        assertThat(call("POST", "/api/v1/accounts", partnerKey, "{\"currency\":\"NGN\"}").status()).isEqualTo(403);
    }

    @Test
    void partnerEndpointsRejectCallsWithoutAKey() throws Exception {
        assertThat(call("GET", "/api/v1/partner/me", null, null).status()).isEqualTo(401);
    }

    @Test
    void aKeyWithAnUnknownPrefixIsRejected() throws Exception {
        assertThat(call("GET", "/api/v1/accounts", "xyz_not-a-real-key", null).status()).isEqualTo(401);
        assertThat(call("GET", "/api/v1/partner/me", "pbp_not-a-real-key", null).status()).isEqualTo(401);
    }

    @Test
    void registeringTheSamePartnerNameTwiceIsRefused() throws Exception {
        String name = uniquePartnerName();
        assertThat(registerPartner(name).status()).isEqualTo(201);

        assertThat(registerPartner(name).status()).isEqualTo(409);
    }

    // ---- helpers ----

    private static String uniquePartnerName() {
        return "Partner " + UUID.randomUUID();
    }

    private Response registerPartner(String name) throws Exception {
        String json = """
                {"name":"%s","contactEmail":"ops@example.com"}
                """.formatted(name);
        return call("POST", "/api/v1/partner/register", null, json);
    }

    private String registerCustomerKey() throws Exception {
        String json = """
                {"fullName":"Test User","email":"user-%s@example.com"}
                """.formatted(UUID.randomUUID());
        Response response = call("POST", "/api/v1/customers/register", null, json);
        assertThat(response.status()).isEqualTo(201);
        return field(response.body(), "apiKey");
    }

    private static String field(String json, String name) {
        Matcher matcher = Pattern.compile("\"" + name + "\"\\s*:\\s*\"([^\"]*)\"").matcher(json);
        assertThat(matcher.find()).as("field %s in %s", name, json).isTrue();
        return matcher.group(1);
    }

    private Response call(String method, String path, String apiKey, String json) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json");
        if (apiKey != null) {
            builder.header("X-API-Key", apiKey);
        }
        builder.method(method, json == null
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(json));

        HttpResponse<String> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        return new Response(response.statusCode(), response.body());
    }
}