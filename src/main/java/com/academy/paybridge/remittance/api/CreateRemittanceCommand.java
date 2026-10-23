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
        String recipientAccountNumber,  // a PayBridge account, or a bank account when a bank code is given
        String recipientBankCode) {     // null means "pay into a PayBridge account"

    public CreateRemittanceCommand(UUID partnerId, String idempotencyKey, String partnerReference, UUID quoteId,
                                   String senderName, String senderCountry, String recipientAccountNumber) {
        this(partnerId, idempotencyKey, partnerReference, quoteId, senderName, senderCountry,
                recipientAccountNumber, null);
    }

    public boolean isBankPayout() {
        return recipientBankCode != null && !recipientBankCode.isBlank();
    }
}