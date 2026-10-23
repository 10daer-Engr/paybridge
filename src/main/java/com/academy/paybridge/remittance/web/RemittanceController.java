package com.academy.paybridge.remittance.web;

import com.academy.paybridge.partner.api.CurrentPartner;
import com.academy.paybridge.remittance.api.CreateRemittanceCommand;
import com.academy.paybridge.remittance.api.RemittanceApi;
import com.academy.paybridge.remittance.api.RemittanceResult;
import com.academy.paybridge.shared.idempotency.IdempotencyKeys;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/partner/remittances")
@Tag(name = "Partner Remittances")
public class RemittanceController {

    private final RemittanceApi remittanceApi;

    public RemittanceController(RemittanceApi remittanceApi) {
        this.remittanceApi = remittanceApi;
    }

    @PostMapping
    @Operation(summary = "Send a remittance: redeem a quote and deliver naira",
            description = "Without recipientBankCode the naira lands in a PayBridge account at once (201). "
                    + "With it, PayBridge pays a Nigerian bank account through Paystack: you get 201 once final, "
                    + "or 202 while the payout is PROCESSING. A failed payout is reversed and your credit restored. "
                    + "Send the same Idempotency-Key when retrying: a replay returns 200 with replayed=true.")
    public ResponseEntity<RemittanceResponse> create(
            @Parameter(description = "Unique string for this intended remittance", example = "rem-001")
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody CreateRemittanceRequest request) {

        UUID partnerId = CurrentPartner.id();
        RemittanceResult result = remittanceApi.create(new CreateRemittanceCommand(
                partnerId,
                IdempotencyKeys.scoped(partnerId, idempotencyKey),
                request.partnerReference(),
                request.quoteId(),
                request.senderName(),
                request.senderCountry(),
                request.recipientAccountNumber(),
                request.recipientBankCode()));

        HttpStatus status;
        if (result.replayed()) {
            status = HttpStatus.OK;
        } else if ("PROCESSING".equals(result.remittance().status())) {
            status = HttpStatus.ACCEPTED;
        } else {
            status = HttpStatus.CREATED;
        }
        return ResponseEntity.status(status).body(RemittanceResponse.from(result));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get one of your remittances and its status")
    public RemittanceResponse get(@PathVariable UUID id) {
        return RemittanceResponse.from(remittanceApi.get(id, CurrentPartner.id()), false);
    }

    @PostMapping("/{id}/refresh")
    @Operation(summary = "Ask the provider about a PROCESSING bank payout and settle it",
            description = "Safe to call repeatedly. Does nothing if the remittance is already final.")
    public RemittanceResponse refresh(@PathVariable UUID id) {
        return RemittanceResponse.from(remittanceApi.refresh(id, CurrentPartner.id()), false);
    }

    @GetMapping
    @Operation(summary = "Your 20 most recent remittances, newest first")
    public List<RemittanceResponse> recent() {
        return remittanceApi.recent(CurrentPartner.id()).stream()
                .map(v -> RemittanceResponse.from(v, false))
                .toList();
    }
}