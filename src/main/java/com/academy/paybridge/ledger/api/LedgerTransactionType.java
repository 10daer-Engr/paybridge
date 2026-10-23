package com.academy.paybridge.ledger.api;

public enum LedgerTransactionType {
    FUNDING,
    TRANSFER,
    EXTERNAL_PAYOUT,
    REVERSAL,
    CONVERSION,     // one currency's side of an exchange
    SETTLEMENT      // a partner paying what it owes
}