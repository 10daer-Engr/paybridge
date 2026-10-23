package com.academy.paybridge.remittance.web;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.UUID;

public record CreateRemittanceRequest(
        @Schema(description = "The quote to redeem (from POST /partner/fx/quotes)")
        @NotNull UUID quoteId,

        @Schema(example = "John Smith")
        @NotBlank @Size(max = 150) String senderName,

        @Schema(description = "Sender's country as a two-letter ISO code", example = "GB")
        @NotBlank @Pattern(regexp = "[A-Z]{2}", message = "must be a two-letter uppercase country code") String senderCountry,

        @Schema(description = "The recipient's 10-digit account number: a PayBridge account, or a bank account if recipientBankCode is set",
                example = "0123456789")
        @NotBlank @Pattern(regexp = "\\d{10}", message = "must be exactly 10 digits") String recipientAccountNumber,

        @Schema(description = "Leave empty to pay into a PayBridge account. Set it to pay a Nigerian bank account. "
                + "In this sandbox use 001 (the test bank) with any 10-digit number.", example = "001")
        @Pattern(regexp = "\\d{3,6}", message = "must be 3 to 6 digits") String recipientBankCode,

        @Schema(description = "Your own reference for this payment. Optional, and unique per partner.",
                example = "INV-2026-0042")
        @Size(max = 64) String partnerReference) {
}