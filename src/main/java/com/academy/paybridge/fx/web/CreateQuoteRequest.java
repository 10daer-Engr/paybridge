package com.academy.paybridge.fx.web;

import com.academy.paybridge.shared.money.Currency;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

public record CreateQuoteRequest(
        @Schema(description = "Amount the sender pays, in the send currency, at most 2 decimals", example = "100.00")
        @NotNull @DecimalMin("0.01") @DecimalMax("1000000.00") BigDecimal sendAmount,

        @Schema(example = "USD") @NotNull Currency sendCurrency,

        @Schema(example = "NGN") @NotNull Currency receiveCurrency) {
}