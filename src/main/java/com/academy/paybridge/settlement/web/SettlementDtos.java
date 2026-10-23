package com.academy.paybridge.settlement.web;

import com.academy.paybridge.settlement.api.PaymentView;
import com.academy.paybridge.settlement.api.ReconciliationReport;
import com.academy.paybridge.settlement.api.SettlementDetail;
import com.academy.paybridge.settlement.api.SettlementItemView;
import com.academy.paybridge.settlement.api.SettlementView;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class SettlementDtos {

    private SettlementDtos() {
    }

    // ---- requests ----

    public record PaymentRequest(
            @Schema(description = "USD received, at most 2 decimals", example = "100.00")
            @NotNull @DecimalMin("0.01") @DecimalMax("100000000.00") BigDecimal amount,

            @Schema(description = "The bank's reference for the incoming wire", example = "WIRE-2026-1016-001")
            @NotBlank @Size(max = 64) String wireReference) {
    }

    public record StatementLineRequest(
            @Schema(description = "Your partnerReference for the remittance, or its id if you sent none", example = "INV-1")
            @NotBlank @Size(max = 64) String reference,

            @Schema(description = "The USD amount you show for it", example = "100.00")
            @NotNull @DecimalMin("0.00") @DecimalMax("100000000.00") BigDecimal amount) {
    }

    public record ReconcileRequest(
            @NotNull @Size(max = 1000) List<@Valid StatementLineRequest> lines) {
    }

    // ---- responses ----

    public record SettlementResponse(
            UUID id, String reference, String status, String reconciliationStatus, int itemCount,
            String grossUsd, String feeUsd, String convertedUsd, String payoutNgn,
            String paidUsd, String outstandingUsd, Instant createdAt, Instant closedAt) {

        static SettlementResponse from(SettlementView v) {
            return new SettlementResponse(
                    v.id(), v.reference(), v.status(), v.reconciliationStatus(), v.itemCount(),
                    v.gross().toMajor().toPlainString(), v.fee().toMajor().toPlainString(),
                    v.converted().toMajor().toPlainString(), v.payout().toMajor().toPlainString(),
                    v.paid().toMajor().toPlainString(), v.outstanding().toMajor().toPlainString(),
                    v.createdAt(), v.closedAt());
        }
    }

    public record ItemResponse(UUID remittanceId, String reference, String sendUsd, String feeUsd,
                               String receiveNgn, Instant completedAt) {

        static ItemResponse from(SettlementItemView i) {
            return new ItemResponse(i.remittanceId(), i.reference(),
                    i.sendAmount().toMajor().toPlainString(), i.fee().toMajor().toPlainString(),
                    i.receiveAmount().toMajor().toPlainString(), i.completedAt());
        }
    }

    public record DetailResponse(SettlementResponse settlement, List<ItemResponse> items) {

        static DetailResponse from(SettlementDetail d) {
            return new DetailResponse(SettlementResponse.from(d.settlement()),
                    d.items().stream().map(ItemResponse::from).toList());
        }
    }

    public record PaymentResponse(UUID id, String wireReference, String amountUsd, Instant createdAt,
                                  boolean replayed, SettlementResponse settlement) {

        static PaymentResponse from(PaymentView p) {
            return new PaymentResponse(p.id(), p.wireReference(), p.amount().toMajor().toPlainString(),
                    p.createdAt(), p.replayed(), SettlementResponse.from(p.settlement()));
        }
    }

    public record BreakResponse(String kind, String reference, String ourAmountUsd, String theirAmountUsd) {
    }

    public record ReconciliationResponse(UUID runId, String status, int statementLines,
                                         String statementTotalUsd, String ourTotalUsd,
                                         List<BreakResponse> breaks, SettlementResponse settlement) {

        static ReconciliationResponse from(ReconciliationReport r) {
            return new ReconciliationResponse(
                    r.runId(), r.status(), r.statementLines(),
                    r.statementTotal().toMajor().toPlainString(), r.ourTotal().toMajor().toPlainString(),
                    r.breaks().stream().map(b -> new BreakResponse(b.kind(), b.reference(),
                            b.ourAmount() == null ? null : b.ourAmount().toMajor().toPlainString(),
                            b.theirAmount() == null ? null : b.theirAmount().toMajor().toPlainString())).toList(),
                    SettlementResponse.from(r.settlement()));
        }
    }
}