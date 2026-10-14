package com.academy.paybridge.fx.client;

import com.academy.paybridge.fx.config.FxProperties;
import com.academy.paybridge.shared.money.Currency;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;

/**
 * A deterministic fake market: the rate swings +/-1% around a base on a 60-minute triangle wave.
 * Integer arithmetic only, so a given minute always gives the same rate.
 */
@Component
class SimulatedRateProvider implements RateProvider {

    private static final long PERIOD_MINUTES = 60;
    private static final long AMPLITUDE_BPS = 100;

    private final FxProperties properties;
    private final Clock clock;

    SimulatedRateProvider(FxProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    public MidRate currentMidRate(Currency from, Currency to) {
        Instant now = clock.instant();

        BigDecimal rate;
        String fixed = properties.fixedMidRate();
        if (fixed != null && !fixed.isBlank()) {
            rate = new BigDecimal(fixed.trim()).setScale(4, RoundingMode.HALF_EVEN);
        } else {
            long minute = now.getEpochSecond() / 60;
            long phase = minute % PERIOD_MINUTES;                      // 0..59
            long triangle = phase < 30 ? phase : 60 - phase;           // 0 up to 30 and back to 0
            long offsetBps = (triangle * 2 - 30) * AMPLITUDE_BPS / 30; // -100 .. +100
            rate = properties.simulatedBaseRate()
                    .multiply(BigDecimal.valueOf(10_000 + offsetBps))
                    .divide(BigDecimal.valueOf(10_000), 4, RoundingMode.HALF_EVEN);
        }
        return new MidRate(rate, now, "SIMULATED");
    }
}