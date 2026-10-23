package com.academy.paybridge.settlement.api;

import com.academy.paybridge.shared.money.Money;

import java.time.Instant;
import java.util.UUID;

public record SettlementView(
        UUID id,
        String reference,
        String status,                  // OPEN or CLOSED
        String reconciliationStatus,    // PENDING, MATCHED or BREAKS
        int itemCount,
        Money gross,                    // what the partner owes: the sum of the send amounts (fees included)
        Money fee,                      // PayBridge's fees inside that
        Money converted,                // gross minus fee: the part that was exchanged
        Money payout,                   // naira delivered to recipients for these remittances
        Money paid,
        Money outstanding,
        Instant createdAt,
        Instant closedAt) {
}