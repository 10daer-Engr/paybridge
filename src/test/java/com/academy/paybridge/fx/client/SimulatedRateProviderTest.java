package com.academy.paybridge.fx.client;

import com.academy.paybridge.fx.config.FxProperties;
import com.academy.paybridge.shared.money.Currency;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class SimulatedRateProviderTest {

    private static FxProperties properties(String fixedRate) {
        return new FxProperties(150, new BigDecimal("1.00"), Duration.ofMinutes(2),
                new BigDecimal("5.00"), new BigDecimal("2000.00"), new BigDecimal("1500.00"), fixedRate);
    }

    private static SimulatedRateProvider at(long minute, String fixedRate) {
        Clock clock = Clock.fixed(Instant.ofEpochSecond(minute * 60), ZoneOffset.UTC);
        return new SimulatedRateProvider(properties(fixedRate), clock);
    }

    @Test
    void theSimulatedMarketSwingsOneOnePercentEitherSideOfTheBase() {
        assertThat(at(0, null).currentMidRate(Currency.USD, Currency.NGN).rate()).isEqualByComparingTo("1485.0000");
        assertThat(at(15, null).currentMidRate(Currency.USD, Currency.NGN).rate()).isEqualByComparingTo("1500.0000");
        assertThat(at(30, null).currentMidRate(Currency.USD, Currency.NGN).rate()).isEqualByComparingTo("1515.0000");
    }

    @Test
    void theSameMinuteAlwaysGivesTheSameRate() {
        BigDecimal first = at(41, null).currentMidRate(Currency.USD, Currency.NGN).rate();
        BigDecimal second = at(41, null).currentMidRate(Currency.USD, Currency.NGN).rate();

        assertThat(first).isEqualByComparingTo(second);
    }

    @Test
    void aFixedRateFreezesTheMarket() {
        assertThat(at(0, "1620.50").currentMidRate(Currency.USD, Currency.NGN).rate()).isEqualByComparingTo("1620.5000");
        assertThat(at(30, "1620.50").currentMidRate(Currency.USD, Currency.NGN).rate()).isEqualByComparingTo("1620.5000");
    }
}