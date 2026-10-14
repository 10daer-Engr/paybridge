package com.academy.paybridge.fx.api;

import com.academy.paybridge.shared.money.Currency;

import java.math.BigDecimal;
import java.time.Instant;

/** An indicative rate. Only a quote guarantees one. */
public record CurrentRate(Currency from, Currency to, BigDecimal midRate, BigDecimal customerRate,
                          int spreadBps, Instant asOf, String source) {
}