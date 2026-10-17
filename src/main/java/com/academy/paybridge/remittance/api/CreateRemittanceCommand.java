package com.academy.paybridge.remittance.api;

import java.util.UUID;

/** The idempotency key is expected already scoped to the partner. */
public record CreateRemittanceCommand(
        UUID partnerId,
        String idempotencyKey,
        String partnerReference,        // the partner's own payment reference, optional
        UUID quoteId,
        String senderName,
        String senderCountry,
        String recipientAccountNumber) {
}