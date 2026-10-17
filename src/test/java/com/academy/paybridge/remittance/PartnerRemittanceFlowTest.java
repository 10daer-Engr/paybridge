package com.academy.paybridge.remittance;

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
class PartnerRemittanceFlowTest {

    @Value("${local.server.port}")
    int port;

    private final HttpClient http = HttpClient.newHttpClient();

    private record Response(int status, String body) {
    }

    @Test
    void aPartnerPaysARecipientAndBothSidesSeeTheResult() throws Exception {
        // A customer with a naira account, and a partner abroad.
        String customerKey = registerCustomerKey();
        String recipientNumber = field(call("POST", "/api/v1/accounts", customerKey, null,
                "{\"currency\":\"NGN\"}").body(), "accountNumber");
        String partnerKey = registerPartnerKey();

        // The partner quotes $100 and sends.
        String quoteId = field(call("POST", "/api/v1/partner/fx/quotes", partnerKey, null,
                "{\"sendAmount\":100.00,\"sendCurrency\":\"USD\",\"receiveCurrency\":\"NGN\"}").body(), "quoteId");
        String body = """
                {"quoteId":"%s","senderName":"John Smith","senderCountry":"GB",
                 "recipientAccountNumber":"%s","partnerReference":"INV-1"}
                """.formatted(quoteId, recipientNumber);

        Response created = call("POST", "/api/v1/partner/remittances", partnerKey, "rem-1", body);
        assertThat(created.status()).isEqualTo(201);
        assertThat(created.body())
                .contains("\"status\":\"COMPLETED\"")
                .contains("\"receiveAmount\":\"146272.50\"")
                .contains("\"replayed\":false");
        String remittanceId = field(created.body(), "id");

        // A retry with the same key is a replay, not a second payment.
        Response replay = call("POST", "/api/v1/partner/remittances", partnerKey, "rem-1", body);
        assertThat(replay.status()).isEqualTo(200);
        assertThat(replay.body()).contains("\"replayed\":true");

        // The recipient sees the naira.
        Response account = call("GET", "/api/v1/accounts/" + recipientNumber, customerKey, null, null);
        assertThat(account.body()).contains("\"balance\":\"146272.50\"");

        // The partner sees what it now owes, and can read its remittance back.
        Response me = call("GET", "/api/v1/partner/me", partnerKey, null, null);
        assertThat(me.body()).contains("\"owedUsd\":\"100.00\"").contains("\"availableCreditUsd\":\"9900.00\"");
        assertThat(call("GET", "/api/v1/partner/remittances/" + remittanceId, partnerKey, null, null).status())
                .isEqualTo(200);

        // Other callers cannot see it, or use the endpoint at all.
        assertThat(call("GET", "/api/v1/partner/remittances/" + remittanceId, registerPartnerKey(), null, null)
                .status()).isEqualTo(404);
        assertThat(call("POST", "/api/v1/partner/remittances", customerKey, "rem-2", body).status()).isEqualTo(403);
    }

    // ---- helpers ----

    private String registerCustomerKey() throws Exception {
        String json = "{\"fullName\":\"Test User\",\"email\":\"user-" + UUID.randomUUID() + "@example.com\"}";
        Response response = call("POST", "/api/v1/customers/register", null, null, json);
        assertThat(response.status()).isEqualTo(201);
        return field(response.body(), "apiKey");
    }

    private String registerPartnerKey() throws Exception {
        String json = "{\"name\":\"Partner " + UUID.randomUUID() + "\",\"contactEmail\":\"ops@example.com\"}";
        Response response = call("POST", "/api/v1/partner/register", null, null, json);
        assertThat(response.status()).isEqualTo(201);
        return field(response.body(), "apiKey");
    }

    private static String field(String json, String name) {
        Matcher matcher = Pattern.compile("\"" + name + "\"\\s*:\\s*\"([^\"]*)\"").matcher(json);
        assertThat(matcher.find()).as("field %s in %s", name, json).isTrue();
        return matcher.group(1);
    }

    private Response call(String method, String path, String apiKey, String idempotencyKey, String json)
            throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json");
        if (apiKey != null) {
            builder.header("X-API-Key", apiKey);
        }
        if (idempotencyKey != null) {
            builder.header("Idempotency-Key", idempotencyKey);
        }
        builder.method(method, json == null
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(json));

        HttpResponse<String> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        return new Response(response.statusCode(), response.body());
    }
}