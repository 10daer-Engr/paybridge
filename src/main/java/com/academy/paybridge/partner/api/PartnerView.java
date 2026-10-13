package com.academy.paybridge.partner.api;

import com.academy.paybridge.shared.money.Money;

import java.util.UUID;

public record PartnerView(UUID id, String name, Money creditLimit) {
}