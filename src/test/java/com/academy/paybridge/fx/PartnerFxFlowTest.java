package com.academy.paybridge.fx;

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
class PartnerFxFlowTest {

    @Value("${local.server.port}")
    int port;

    private final HttpClient http = HttpClient.newHttpClient();

    private record Response(int status, String body) {
    }

    @Test
    void aPartnerSeesTheRateAndItsSpread() throws Exception {
        String key = partnerKey();

        Response response = call("GET", "/api/v1/partner/fx/rate?from=USD&to=NGN", key, null);

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.body())
                .contains("\"midRate\":\"1500.0000\"")
                .contains("\"customerRate\":\"1477.500000\"")
                .contains("\"spreadBps\":150");
    }

    @Test
    void aQuoteShowsWhatTheRecipientGetsButNeverOurMargin() throws Exception {
        String key = partnerKey();

        Response response = call("POST", "/api/v1/partner/fx/quotes", key,
                "{\"sendAmount\":100.00,\"sendCurrency\":\"USD\",\"receiveCurrency\":\"NGN\"}");

        assertThat(response.status()).isEqualTo(201);
        assertThat(response.body())
                .contains("\"fee\":\"1.00\"")
                .contains("\"convertedAmount\":\"99.00\"")
                .contains("\"receiveAmount\":\"146272.50\"")
                .contains("\"status\":\"ACTIVE\"")
                .doesNotContain("margin");
    }

    @Test
    void anotherPartnerCannotSeeMyQuote() throws Exception {
        String owner = partnerKey();
        String other = partnerKey();
        Response created = call("POST", "/api/v1/partner/fx/quotes", owner,
                "{\"sendAmount\":50.00,\"sendCurrency\":\"USD\",\"receiveCurrency\":\"NGN\"}");
        String quoteId = field(created.body(), "quoteId");

        assertThat(call("GET", "/api/v1/partner/fx/quotes/" + quoteId, owner, null).status()).isEqualTo(200);
        assertThat(call("GET", "/api/v1/partner/fx/quotes/" + quoteId, other, null).status()).isEqualTo(404);
    }

    @Test
    void aCustomerKeyCannotAskForQuotes() throws Exception {
        String json = "{\"fullName\":\"Test User\",\"email\":\"user-" + UUID.randomUUID() + "@example.com\"}";
        String customerKey = field(call("POST", "/api/v1/customers/register", null, json).body(), "apiKey");

        assertThat(call("GET", "/api/v1/partner/fx/rate?from=USD&to=NGN", customerKey, null).status()).isEqualTo(403);
    }

    // ---- helpers ----

    private String partnerKey() throws Exception {
        String json = "{\"name\":\"Partner " + UUID.randomUUID() + "\",\"contactEmail\":\"ops@example.com\"}";
        Response response = call("POST", "/api/v1/partner/register", null, json);
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