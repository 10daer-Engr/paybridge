package com.academy.paybridge.fx.api;

import com.academy.paybridge.shared.money.Currency;
import com.academy.paybridge.shared.money.Money;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Internal view. It includes the margin, so web layers must not pass it to partners. */
public record QuoteView(
        UUID id,
        Currency fromCurrency,
        Currency toCurrency,
        Money sendAmount,
        Money fee,
        Money convertedAmount,
        BigDecimal midRate,
        BigDecimal customerRate,
        int spreadBps,
        Money receiveAmount,
        Money margin,
        Instant createdAt,
        Instant expiresAt,
        String status) {   // ACTIVE, USED or EXPIRED
}