package com.academy.paybridge.settlement.web;

import com.academy.paybridge.partner.api.CurrentPartner;
import com.academy.paybridge.settlement.api.RecordPaymentCommand;
import com.academy.paybridge.settlement.web.SettlementDtos.PaymentRequest;
import com.academy.paybridge.settlement.web.SettlementDtos.PaymentResponse;
import com.academy.paybridge.settlement.api.SettlementApi;
import com.academy.paybridge.shared.idempotency.IdempotencyKeys;
import com.academy.paybridge.shared.money.Currency;
import com.academy.paybridge.shared.money.Money;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/partner/settlements")
@Tag(name = "Partner Settlements")
@ConditionalOnProperty(prefix = "paybridge.sandbox", name = "settlement-payments-enabled", havingValue = "true")
public class SandboxSettlementPaymentController {

    private final SettlementApi settlementApi;

    public SandboxSettlementPaymentController(SettlementApi settlementApi) {
        this.settlementApi = settlementApi;
    }

    @PostMapping("/{id}/payments")
    @Operation(summary = "Sandbox only: record a USD payment against a settlement",
            description = "Simulates PayBridge finance matching an incoming wire to this batch. Posts to the ledger: "
                    + "the USD nostro account is debited and your position account credited, restoring your available credit. "
                    + "A payment may not exceed the outstanding amount. Send the same Idempotency-Key when retrying.")
    public PaymentResponse pay(
            @PathVariable UUID id,
            @Parameter(description = "Unique string for this payment", example = "pay-001")
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody PaymentRequest request) {

        UUID partnerId = CurrentPartner.id();
        return PaymentResponse.from(settlementApi.recordPayment(new RecordPaymentCommand(
                partnerId, id, IdempotencyKeys.scoped(partnerId, idempotencyKey),
                Money.ofMajor(request.amount(), Currency.USD), request.wireReference())));
    }
}