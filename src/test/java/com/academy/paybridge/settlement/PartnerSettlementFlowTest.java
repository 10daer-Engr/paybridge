package com.academy.paybridge.settlement;

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
class PartnerSettlementFlowTest {

    @Value("${local.server.port}")
    int port;

    private final HttpClient http = HttpClient.newHttpClient();

    private record Response(int status, String body) {
    }

    @Test
    void aPartnerSettlesAndReconcilesARemittanceEndToEnd() throws Exception {
        String customerKey = registerCustomerKey();
        String recipient = field(call("POST", "/api/v1/accounts", customerKey, null,
                "{\"currency\":\"NGN\"}").body(), "accountNumber");
        String partnerKey = registerPartnerKey();

        // A $100 remittance into the customer's account.
        String quoteId = field(call("POST", "/api/v1/partner/fx/quotes", partnerKey, null,
                "{\"sendAmount\":100.00,\"sendCurrency\":\"USD\",\"receiveCurrency\":\"NGN\"}").body(), "quoteId");
        String remittance = """
                {"quoteId":"%s","senderName":"John Smith","senderCountry":"GB",
                 "recipientAccountNumber":"%s","partnerReference":"INV-1"}
                """.formatted(quoteId, recipient);
        assertThat(call("POST", "/api/v1/partner/remittances", partnerKey, "rem-1", remittance).status())
                .isEqualTo(201);

        // The partner owes $100.00 and wants to settle.
        assertThat(call("GET", "/api/v1/partner/me", partnerKey, null, null).body())
                .contains("\"owedUsd\":\"100.00\"");
        Response batch = call("POST", "/api/v1/partner/settlements", partnerKey, null, null);
        assertThat(batch.status()).isEqualTo(201);
        assertThat(batch.body()).contains("\"grossUsd\":\"100.00\"").contains("\"status\":\"OPEN\"");
        String batchId = field(batch.body(), "id");

        // Nothing more to settle.
        assertThat(call("POST", "/api/v1/partner/settlements", partnerKey, null, null).status()).isEqualTo(422);

        // The dollars arrive.
        Response payment = call("POST", "/api/v1/partner/settlements/" + batchId + "/payments", partnerKey, "pay-1",
                "{\"amount\":100.00,\"wireReference\":\"WIRE-1\"}");
        assertThat(payment.status()).isEqualTo(200);
        assertThat(payment.body()).contains("\"paidUsd\":\"100.00\"").contains("\"replayed\":false");

        // Paid, but not reconciled: still open.
        assertThat(call("GET", "/api/v1/partner/settlements/" + batchId, partnerKey, null, null).body())
                .contains("\"status\":\"OPEN\"");

        // A wrong statement shows breaks; the right one closes the batch.
        Response wrong = call("POST", "/api/v1/partner/settlements/" + batchId + "/reconciliation", partnerKey, null,
                "{\"lines\":[{\"reference\":\"INV-1\",\"amount\":90.00}]}");
        assertThat(wrong.body()).contains("\"status\":\"BREAKS\"").contains("AMOUNT_MISMATCH");

        Response right = call("POST", "/api/v1/partner/settlements/" + batchId + "/reconciliation", partnerKey, null,
                "{\"lines\":[{\"reference\":\"INV-1\",\"amount\":100.00}]}");
        assertThat(right.body()).contains("\"status\":\"MATCHED\"");
        assertThat(call("GET", "/api/v1/partner/settlements/" + batchId, partnerKey, null, null).body())
                .contains("\"status\":\"CLOSED\"");

        // The credit is fully restored.
        assertThat(call("GET", "/api/v1/partner/me", partnerKey, null, null).body())
                .contains("\"owedUsd\":\"0.00\"").contains("\"availableCreditUsd\":\"10000.00\"");

        // Customers cannot use settlement endpoints.
        assertThat(call("POST", "/api/v1/partner/settlements", customerKey, null, null).status()).isEqualTo(403);
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