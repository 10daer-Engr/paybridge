package com.academy.paybridge.settlement.api;

import com.academy.paybridge.shared.money.Money;

import java.time.Instant;
import java.util.UUID;

public record PaymentView(UUID id, String wireReference, Money amount, Instant createdAt,
                          SettlementView settlement, boolean replayed) {
}