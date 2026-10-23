package com.academy.paybridge.transfer.client.paystack;

import com.academy.paybridge.shared.exception.BusinessException;
import com.academy.paybridge.shared.money.Currency;
import com.academy.paybridge.transfer.client.GatewayRejectedException;
import com.academy.paybridge.transfer.client.GatewayUnavailableException;
import com.academy.paybridge.transfer.client.TransferGateway;
import com.academy.paybridge.transfer.client.paystack.PaystackDtos.BankData;
import com.academy.paybridge.transfer.client.paystack.PaystackDtos.Envelope;
import com.academy.paybridge.transfer.client.paystack.PaystackDtos.ErrorBody;
import com.academy.paybridge.transfer.client.paystack.PaystackDtos.RecipientData;
import com.academy.paybridge.transfer.client.paystack.PaystackDtos.ResolveData;
import com.academy.paybridge.transfer.client.paystack.PaystackDtos.TransferData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

import java.net.http.HttpClient;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.regex.Pattern;

@ConditionalOnProperty(prefix = "paybridge.provider", name = "mode", havingValue = "paystack", matchIfMissing = true)
@Component
class PaystackTransferGateway implements TransferGateway {

    private static final Logger log = LoggerFactory.getLogger(PaystackTransferGateway.class);

    private static final int MAX_BANK_PAGES = 10;
    private static final Pattern REFERENCE_FORMAT = Pattern.compile("[a-z0-9_-]+");

    private static final ParameterizedTypeReference<Envelope<List<BankData>>> BANKS = new ParameterizedTypeReference<>() { };
    private static final ParameterizedTypeReference<Envelope<ResolveData>> RESOLVE = new ParameterizedTypeReference<>() { };
    private static final ParameterizedTypeReference<Envelope<RecipientData>> RECIPIENT = new ParameterizedTypeReference<>() { };
    private static final ParameterizedTypeReference<Envelope<TransferData>> TRANSFER = new ParameterizedTypeReference<>() { };

    private final PaystackProperties properties;
    private final RestClient client;

    PaystackTransferGateway(PaystackProperties properties) {
        this.properties = properties;

        if (properties.isConfigured() && properties.secretKey().startsWith("sk_live_")) {
            throw new IllegalStateException(
                    "A live Paystack key was detected. PayBridge is a sandbox project: use an sk_test_ key.");
        }

        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.connectTimeout())
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(properties.readTimeout());

        this.client = RestClient.builder()
                .baseUrl(properties.baseUrl())
                .requestFactory(requestFactory)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + properties.secretKey())
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    @Override
    public List<Bank> listBanks() {
        List<Bank> banks = new ArrayList<>();
        String cursor = null;

        for (int page = 0; page < MAX_BANK_PAGES; page++) {
            String currentCursor = cursor;
            Envelope<List<BankData>> body = call(() -> client.get()
                    .uri(uri -> {
                        uri.path("/bank")
                                .queryParam("country", "nigeria")
                                .queryParam("currency", "NGN")
                                .queryParam("perPage", 100)
                                .queryParam("use_cursor", true);
                        if (currentCursor != null) {
                            uri.queryParam("next", currentCursor);
                        }
                        return uri.build();
                    })
                    .retrieve()
                    .body(BANKS));

            if (body == null || body.data() == null) {
                break;
            }
            for (BankData bank : body.data()) {
                if (!Boolean.FALSE.equals(bank.active())) {
                    banks.add(new Bank(bank.name(), bank.code()));
                }
            }
            String next = body.meta() == null ? null : body.meta().next();
            if (next == null || next.isBlank()) {
                break;
            }
            cursor = next;
        }
        return banks;
    }

    @Override
    public ResolvedAccount resolveAccount(String accountNumber, String bankCode) {
        Envelope<ResolveData> body = call(() -> client.get()
                .uri(uri -> uri.path("/bank/resolve")
                        .queryParam("account_number", accountNumber)
                        .queryParam("bank_code", bankCode)
                        .build())
                .retrieve()
                .body(RESOLVE));
        ResolveData data = requireData(body);
        return new ResolvedAccount(data.accountNumber(), data.accountName());
    }

    @Override
    public String createRecipient(String accountName, String accountNumber, String bankCode) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", "nuban");
        payload.put("name", accountName);
        payload.put("account_number", accountNumber);
        payload.put("bank_code", bankCode);
        payload.put("currency", "NGN");

        Envelope<RecipientData> body = call(() -> client.post()
                .uri("/transferrecipient")
                .contentType(MediaType.APPLICATION_JSON)
                .body(payload)
                .retrieve()
                .body(RECIPIENT));
        return requireData(body).recipientCode();
    }

    @Override
    public GatewayTransferResult initiateTransfer(GatewayTransferRequest request) {
        if (request.amount().currency() != Currency.NGN) {
            throw new IllegalArgumentException("Paystack gateway only supports NGN transfers here");
        }
        if (!REFERENCE_FORMAT.matcher(request.reference()).matches()) {
            throw new IllegalArgumentException(
                    "Paystack references may only contain lowercase letters, digits, '-' and '_'");
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("source", "balance");
        payload.put("amount", request.amount().minorUnits());   // Paystack takes kobo
        payload.put("recipient", request.recipientCode());
        payload.put("reason", request.reason());
        payload.put("reference", request.reference());
        payload.put("currency", "NGN");

        Envelope<TransferData> body = call(() -> client.post()
                .uri("/transfer")
                .contentType(MediaType.APPLICATION_JSON)
                .body(payload)
                .retrieve()
                .body(TRANSFER));
        return toResult(body, request.reference());
    }

    @Override
    public Optional<GatewayTransferResult> verifyTransfer(String reference) {
        try {
            Envelope<TransferData> body = call(() -> client.get()
                    .uri("/transfer/verify/{reference}", reference)
                    .retrieve()
                    .body(TRANSFER));
            return Optional.of(toResult(body, reference));
        } catch (GatewayRejectedException e) {
            if (e.getHttpStatus() == 404) {
                return Optional.empty();   // the provider has never heard of this reference
            }
            throw e;
        }
    }

    // ---- helpers ----

    private GatewayTransferResult toResult(Envelope<TransferData> body, String fallbackReference) {
        TransferData data = requireData(body);
        return new GatewayTransferResult(
                data.reference() != null ? data.reference() : fallbackReference,
                data.transferCode(),
                PaystackStatusMapper.map(data.status()),
                body.message());
    }

    private <T> T requireData(Envelope<T> body) {
        if (body == null || body.data() == null) {
            // A 2xx with no usable body: we cannot tell what happened.
            throw new GatewayUnavailableException("Paystack returned an unreadable response", null);
        }
        return body.data();
    }

    /** Runs one provider call and sorts every failure into "definitely refused" or "outcome unknown". */
    private <T> T call(Supplier<T> action) {
        if (!properties.isConfigured()) {
            throw new BusinessException("PROVIDER_NOT_CONFIGURED",
                    "The payment provider key is not configured on this server");
        }
        try {
            return action.get();
        } catch (HttpClientErrorException e) {
            int status = e.getStatusCode().value();
            if (status == 429) {
                throw new GatewayUnavailableException("Paystack rate limit reached, try again shortly", e);
            }
            throw new GatewayRejectedException(status, extractMessage(e, status));
        } catch (RestClientException e) {
            // 5xx, timeouts, connection errors, unparsable bodies: we do not know what happened.
            log.warn("Paystack call failed with an indeterminate outcome: {}", e.getClass().getSimpleName());
            throw new GatewayUnavailableException("Paystack did not give a reliable answer", e);
        }
    }

    private static String extractMessage(HttpClientErrorException e, int status) {
        try {
            ErrorBody body = e.getResponseBodyAs(ErrorBody.class);
            if (body != null && body.message() != null && !body.message().isBlank()) {
                return body.message();
            }
        } catch (RuntimeException ignored) {
            // fall through to the generic message
        }
        return "Paystack rejected the request (HTTP " + status + ")";
    }
}