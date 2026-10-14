package com.academy.paybridge.fx.client;

import com.academy.paybridge.shared.money.Currency;

import java.math.BigDecimal;
import java.time.Instant;

/** Where mid-market rates come from. Swappable: a live feed would be a second implementation. */
public interface RateProvider {

    MidRate currentMidRate(Currency from, Currency to);

    record MidRate(BigDecimal rate, Instant asOf, String source) {
    }
}