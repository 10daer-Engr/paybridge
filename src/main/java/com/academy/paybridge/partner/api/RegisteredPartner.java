package com.academy.paybridge.partner.api;

import com.academy.paybridge.shared.money.Money;

import java.util.UUID;

/** The only time the plain API key is ever available. */
public record RegisteredPartner(UUID partnerId, String name, Money creditLimit,
                                String usdAccountNumber, String apiKey) {
}