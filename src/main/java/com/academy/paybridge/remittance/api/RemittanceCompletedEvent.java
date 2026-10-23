package com.academy.paybridge.remittance.api;

import com.academy.paybridge.shared.money.Money;

import java.time.Instant;
import java.util.UUID;

/** Published inside the transaction that completes a remittance. Other modules may listen. */
public record RemittanceCompletedEvent(
        UUID remittanceId,
        UUID partnerId,
        String partnerReference,   // may be null
        Money sendAmount,
        Money fee,
        Money receiveAmount,
        Instant completedAt) {
}