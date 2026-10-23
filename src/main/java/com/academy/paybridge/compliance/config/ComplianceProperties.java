package com.academy.paybridge.compliance.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;

@ConfigurationProperties(prefix = "paybridge.compliance")
public record ComplianceProperties(
        /** Most one sender may send, in USD, within the window. */
        @DefaultValue("3000.00") BigDecimal senderDailyLimitUsd,
        /** Most one recipient may receive, in USD, within the window. */
        @DefaultValue("5000.00") BigDecimal recipientDailyLimitUsd,
        /** Most one partner may send in total, in USD, within the window. */
        @DefaultValue("50000.00") BigDecimal partnerDailyLimitUsd,
        @DefaultValue("PT24H") Duration window,
        /** Name similarity from which a possible sanctions match is referred to an analyst. */
        @DefaultValue("0.85") double matchThreshold,
        /** A fictional watch list for the simulation. */
        @DefaultValue({"Ivan Sanctionov", "Acme Blocked Trading"}) List<String> blockedNames,
        /** Illustrative only. */
        @DefaultValue({"KP"}) List<String> blockedCountries) {
}