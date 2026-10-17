package com.academy.paybridge.remittance.api;

import com.academy.paybridge.shared.money.Money;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record RemittanceView(
        UUID id,
        String status,
        String partnerReference,
        UUID quoteId,
        String senderName,
        String senderCountry,
        String recipientType,
        String recipientAccountNumber,
        Money sendAmount,
        Money fee,
        Money receiveAmount,
        BigDecimal customerRate,
        String failureReason,
        Instant createdAt,
        Instant completedAt) {
}