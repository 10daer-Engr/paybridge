package com.academy.paybridge.settlement.api;

import com.academy.paybridge.shared.money.Money;

import java.util.UUID;

/** The idempotency key is expected already scoped to the partner. */
public record RecordPaymentCommand(UUID partnerId, UUID batchId, String idempotencyKey,
                                   Money amount, String wireReference) {
}