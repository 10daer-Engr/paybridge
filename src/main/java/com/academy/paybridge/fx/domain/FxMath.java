package com.academy.paybridge.fx.domain;

import com.academy.paybridge.shared.exception.BusinessException;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Every rate calculation and every rounding decision lives here, and nowhere else.
 * Policy: everything rounds DOWN. Sub-kobo remainders stay with PayBridge.
 * Assumes both currencies have two decimal places (USD cents to NGN kobo).
 */
public final class FxMath {

    public static final int RATE_SCALE = 6;
    private static final BigDecimal BPS_DENOMINATOR = BigDecimal.valueOf(10_000);

    private FxMath() {
    }

    /** The mid rate minus our spread, rounded down to 6 decimals. */
    public static BigDecimal customerRate(BigDecimal midRate, int spreadBps) {
        if (midRate.signum() <= 0) {
            throw new IllegalArgumentException("Mid rate must be positive");
        }
        if (spreadBps < 0 || spreadBps >= 10_000) {
            throw new IllegalArgumentException("Spread must be between 0 and 9999 basis points");
        }
        return midRate
                .multiply(BigDecimal.valueOf(10_000L - spreadBps))
                .divide(BPS_DENOMINATOR, RATE_SCALE, RoundingMode.DOWN);
    }

    /** Converts minor units (cents) to the other currency's minor units (kobo), rounding down. */
    public static long convertDown(long minorUnits, BigDecimal rate) {
        return BigDecimal.valueOf(minorUnits)
                .multiply(rate)
                .setScale(0, RoundingMode.DOWN)
                .longValueExact();
    }

    public record Computation(
            long convertedMinor,   // send amount minus fee, in the send currency
            long midValueMinor,    // what the converted amount is worth at the mid rate
            long receiveMinor,     // what the recipient gets at the customer rate
            long marginMinor,      // midValue minus receive: PayBridge's revenue (always >= 0)
            java.math.BigDecimal customerRate) {
    }

    public static Computation compute(long sendMinor, long feeMinor, BigDecimal midRate, int spreadBps) {
        long converted = Math.subtractExact(sendMinor, feeMinor);
        if (converted <= 0) {
            throw new BusinessException("AMOUNT_TOO_SMALL", "The amount does not cover the fee");
        }

        BigDecimal customerRate = customerRate(midRate, spreadBps);
        long midValue = convertDown(converted, midRate);
        long receive = convertDown(converted, customerRate);
        if (receive <= 0) {
            throw new BusinessException("AMOUNT_TOO_SMALL", "The amount is too small to convert");
        }

        // customerRate <= midRate, and rounding down never reverses an ordering, so this is never negative.
        return new Computation(converted, midValue, receive, Math.subtractExact(midValue, receive), customerRate);
    }
}