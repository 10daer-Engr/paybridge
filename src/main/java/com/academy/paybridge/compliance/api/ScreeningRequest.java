package com.academy.paybridge.compliance.api;

import com.academy.paybridge.shared.money.Money;

import java.util.UUID;

public record ScreeningRequest(
        UUID partnerId,
        String subjectRef,       // what is being screened, for the audit trail (an idempotency key)
        String senderName,
        String senderCountry,    // two-letter ISO code, uppercase
        String recipientRef,     // the recipient's account number, with the bank code for bank payouts
        Money amount,            // in USD
        String recipientName) {  // the beneficiary's name when known (bank payouts), otherwise null

    public ScreeningRequest(UUID partnerId, String subjectRef, String senderName, String senderCountry,
                            String recipientRef, Money amount) {
        this(partnerId, subjectRef, senderName, senderCountry, recipientRef, amount, null);
    }
}