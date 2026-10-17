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

        @Schema(description = "The recipient's PayBridge NGN account number", example = "4017203596")
        @NotBlank @Pattern(regexp = "\\d{10}", message = "must be exactly 10 digits") String recipientAccountNumber,

        @Schema(description = "Your own reference for this payment. Optional, and unique per partner.",
                example = "INV-2026-0042")
        @Size(max = 64) String partnerReference) {
}