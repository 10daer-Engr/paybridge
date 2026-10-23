package com.academy.paybridge.transfer.api;

import com.academy.paybridge.shared.money.Money;

public record PayoutRequest(String reference, String recipientCode, Money amount, String reason) {
}