package com.academy.paybridge.fx.web;

import com.academy.paybridge.fx.api.CurrentRate;

import java.time.Instant;

public record RateResponse(String from, String to, String midRate, String customerRate,
                           int spreadBps, Instant asOf, String source) {

    static RateResponse from(CurrentRate r) {
        return new RateResponse(r.from().name(), r.to().name(),
                r.midRate().toPlainString(), r.customerRate().toPlainString(),
                r.spreadBps(), r.asOf(), r.source());
    }
}