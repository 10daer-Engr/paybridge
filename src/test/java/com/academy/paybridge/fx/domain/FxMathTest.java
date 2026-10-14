package com.academy.paybridge.fx.domain;

import com.academy.paybridge.shared.exception.BusinessException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FxMathTest {

    @Test
    void customerRateAppliesTheSpread() {
        assertThat(FxMath.customerRate(new BigDecimal("1500.0000"), 150))
                .isEqualByComparingTo("1477.500000");
    }

    @Test
    void zeroSpreadGivesTheMidRate() {
        assertThat(FxMath.customerRate(new BigDecimal("1500.5000"), 0))
                .isEqualByComparingTo("1500.500000");
    }

    @Test
    void anAwkwardRateRoundsDownNotToNearest() {
        // 1523.4567 x 0.985 = 1500.6048495 exactly. Down to 6 places is ...849, not ...850.
        assertThat(FxMath.customerRate(new BigDecimal("1523.4567"), 150))
                .isEqualByComparingTo("1500.604849");
    }

    @Test
    void convertDownDropsTheSubKoboRemainder() {
        assertThat(FxMath.convertDown(1, new BigDecimal("1500.604849"))).isEqualTo(1500);
        assertThat(FxMath.convertDown(3, new BigDecimal("0.333333"))).isZero();
    }

    @Test
    void theWorkedExampleFromThePlan() {
        FxMath.Computation c = FxMath.compute(10_000, 100, new BigDecimal("1500.0000"), 150);

        assertThat(c.convertedMinor()).isEqualTo(9_900);
        assertThat(c.midValueMinor()).isEqualTo(14_850_000);
        assertThat(c.receiveMinor()).isEqualTo(14_627_250);
        assertThat(c.marginMinor()).isEqualTo(222_750);
        assertThat(c.customerRate()).isEqualByComparingTo("1477.5");
    }

    @Test
    void marginIsNeverNegativeAndAlwaysBalances() {
        BigDecimal[] mids = {new BigDecimal("1400.1234"), new BigDecimal("1500.0000"), new BigDecimal("1623.9999")};
        int[] spreads = {0, 1, 150, 2500};

        for (BigDecimal mid : mids) {
            for (int spread : spreads) {
                for (long send = 150; send < 5_000_000; send += 7_919) {
                    FxMath.Computation c = FxMath.compute(send, 100, mid, spread);

                    assertThat(c.marginMinor()).isGreaterThanOrEqualTo(0);
                    // The three legs must add up exactly, or the ledger postings would not balance.
                    assertThat(c.receiveMinor() + c.marginMinor()).isEqualTo(c.midValueMinor());
                }
            }
        }
    }

    @Test
    void aFeeThatSwallowsTheWholeAmountIsRejected() {
        assertThatThrownBy(() -> FxMath.compute(100, 100, new BigDecimal("1500"), 150))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getCode()).isEqualTo("AMOUNT_TOO_SMALL"));
    }

    @Test
    void nonsenseSpreadsAreRefused() {
        assertThatThrownBy(() -> FxMath.customerRate(new BigDecimal("1500"), 10_000))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> FxMath.customerRate(new BigDecimal("1500"), -1))
                .isInstanceOf(IllegalArgumentException.class);
    }
}