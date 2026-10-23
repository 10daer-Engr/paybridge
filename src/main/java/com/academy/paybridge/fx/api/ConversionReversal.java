package com.academy.paybridge.fx.api;

import java.util.UUID;

public record ConversionReversal(UUID usdLedgerTransactionId, UUID ngnLedgerTransactionId) {
}