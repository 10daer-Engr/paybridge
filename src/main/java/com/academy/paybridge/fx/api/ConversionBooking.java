package com.academy.paybridge.fx.api;

import java.util.UUID;

public record ConversionBooking(QuoteView quote, UUID usdLedgerTransactionId, UUID ngnLedgerTransactionId) {
}