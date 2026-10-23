package com.academy.paybridge.settlement.api;

import com.academy.paybridge.shared.money.Money;

public record StatementLine(String reference, Money amount) {
}