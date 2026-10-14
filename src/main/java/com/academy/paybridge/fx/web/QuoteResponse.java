package com.academy.paybridge.fx.web;

import com.academy.paybridge.fx.api.QuoteView;

import java.time.Instant;
import java.util.UUID;

public record QuoteResponse(
        UUID quoteId,
        String sendCurrency,
        String receiveCurrency,
        String sendAmount,
        String fee,
        String convertedAmount,
        String midRate,
        String customerRate,
        int spreadBps,
        String receiveAmount,
        Instant createdAt,
        Instant expiresAt,
        String status) {

    static QuoteResponse from(QuoteView q) {
        return new QuoteResponse(
                q.id(),
                q.fromCurrency().name(),
                q.toCurrency().name(),
                q.sendAmount().toMajor().toPlainString(),
                q.fee().toMajor().toPlainString(),
                q.convertedAmount().toMajor().toPlainString(),
                q.midRate().toPlainString(),
                q.customerRate().toPlainString(),
                q.spreadBps(),
                q.receiveAmount().toMajor().toPlainString(),
                q.createdAt(),
                q.expiresAt(),
                q.status());
    }
}