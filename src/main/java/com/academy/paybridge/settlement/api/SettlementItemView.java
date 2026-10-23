package com.academy.paybridge.settlement.api;

import com.academy.paybridge.shared.money.Money;

import java.time.Instant;
import java.util.UUID;

public record SettlementItemView(UUID remittanceId, String reference, Money sendAmount, Money fee,
                                 Money receiveAmount, Instant completedAt) {
}