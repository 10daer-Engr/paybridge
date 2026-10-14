package com.academy.paybridge.fx.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.math.BigDecimal;
import java.time.Duration;

@ConfigurationProperties(prefix = "paybridge.fx")
public record FxProperties(
        /** Our margin on the mid-market rate, in basis points (150 = 1.5%). */
        @DefaultValue("150") int spreadBps,
        /** Flat fee charged on every quote, in the send currency (USD). */
        @DefaultValue("1.00") BigDecimal feeUsd,
        @DefaultValue("PT2M") Duration quoteTtl,
        @DefaultValue("5.00") BigDecimal minSendUsd,
        @DefaultValue("2000.00") BigDecimal maxSendUsd,
        /** Centre of the simulated market. */
        @DefaultValue("1500.00") BigDecimal simulatedBaseRate,
        /** If set, freezes the simulated market at exactly this rate (tests use it). */
        String fixedMidRate) {
}