package com.academy.paybridge.fx;

import com.academy.paybridge.MutableClock;
import com.academy.paybridge.MutableClockConfig;
import com.academy.paybridge.fx.api.FxApi;
import com.academy.paybridge.fx.api.QuoteView;
import com.academy.paybridge.shared.exception.BusinessException;
import com.academy.paybridge.shared.money.Currency;
import com.academy.paybridge.shared.money.Money;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
@Import(MutableClockConfig.class)
class FxQuoteServiceTest {

    private static final Money HUNDRED_DOLLARS = new Money(10_000, Currency.USD);

    @Autowired FxApi fxApi;
    @Autowired TransactionTemplate tx;
    @Autowired MutableClock clock;

    @Test
    void aQuoteForOneHundredDollarsHasTheExpectedAmounts() {
        QuoteView quote = fxApi.createQuote(UUID.randomUUID(), HUNDRED_DOLLARS, Currency.NGN);

        assertThat(quote.fee().minorUnits()).isEqualTo(100);
        assertThat(quote.convertedAmount().minorUnits()).isEqualTo(9_900);
        assertThat(quote.midRate()).isEqualByComparingTo("1500");
        assertThat(quote.customerRate()).isEqualByComparingTo("1477.5");
        assertThat(quote.receiveAmount().minorUnits()).isEqualTo(14_627_250);
        assertThat(quote.margin().minorUnits()).isEqualTo(222_750);
        assertThat(quote.status()).isEqualTo("ACTIVE");
        assertThat(Duration.between(quote.createdAt(), quote.expiresAt())).isEqualTo(Duration.ofMinutes(2));
    }

    @Test
    void aQuoteCanBeUsedOnceWithinItsLifetime() {
        UUID partner = UUID.randomUUID();
        QuoteView quote = fxApi.createQuote(partner, HUNDRED_DOLLARS, Currency.NGN);
        clock.advance(Duration.ofSeconds(119));

        QuoteView used = tx.execute(s -> fxApi.consumeQuote(quote.id(), partner));

        assertThat(used.status()).isEqualTo("USED");
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> fxApi.consumeQuote(quote.id(), partner)))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getCode()).isEqualTo("QUOTE_ALREADY_USED"));
    }

    @Test
    void anExpiredQuoteCannotBeUsed() {
        UUID partner = UUID.randomUUID();
        QuoteView quote = fxApi.createQuote(partner, HUNDRED_DOLLARS, Currency.NGN);
        clock.advance(Duration.ofSeconds(121));

        assertThat(fxApi.getQuote(quote.id(), partner).status()).isEqualTo("EXPIRED");
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> fxApi.consumeQuote(quote.id(), partner)))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getCode()).isEqualTo("QUOTE_EXPIRED"));
    }

    @Test
    void aQuoteBelongsToItsRequesterOnly() {
        QuoteView quote = fxApi.createQuote(UUID.randomUUID(), HUNDRED_DOLLARS, Currency.NGN);
        UUID stranger = UUID.randomUUID();

        assertThatThrownBy(() -> fxApi.getQuote(quote.id(), stranger))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getCode()).isEqualTo("QUOTE_NOT_FOUND"));
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> fxApi.consumeQuote(quote.id(), stranger)))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getCode()).isEqualTo("QUOTE_NOT_FOUND"));
    }

    @Test
    void consumingRequiresAnOpenTransaction() {
        UUID partner = UUID.randomUUID();
        QuoteView quote = fxApi.createQuote(partner, HUNDRED_DOLLARS, Currency.NGN);

        assertThatThrownBy(() -> fxApi.consumeQuote(quote.id(), partner))
                .isInstanceOf(RuntimeException.class);   // no transaction, so the call is refused
    }

    @Test
    void onlyOneOfManyConcurrentRedeemersWins() throws Exception {
        UUID partner = UUID.randomUUID();
        QuoteView quote = fxApi.createQuote(partner, HUNDRED_DOLLARS, Currency.NGN);

        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch startGate = new CountDownLatch(1);
        List<Future<Boolean>> results = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            results.add(pool.submit(() -> {
                startGate.await();
                try {
                    tx.executeWithoutResult(s -> fxApi.consumeQuote(quote.id(), partner));
                    return true;
                } catch (BusinessException e) {
                    return false;
                }
            }));
        }
        startGate.countDown();

        long winners = 0;
        for (Future<Boolean> result : results) {
            if (result.get(30, TimeUnit.SECONDS)) {
                winners++;
            }
        }
        pool.shutdown();

        assertThat(winners).isEqualTo(1);
    }

    @Test
    void amountsOutsideTheAllowedRangeAreRefused() {
        UUID partner = UUID.randomUUID();

        assertThatThrownBy(() -> fxApi.createQuote(partner, new Money(499, Currency.USD), Currency.NGN))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getCode()).isEqualTo("AMOUNT_OUT_OF_RANGE"));
        assertThatThrownBy(() -> fxApi.createQuote(partner, new Money(200_001, Currency.USD), Currency.NGN))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getCode()).isEqualTo("AMOUNT_OUT_OF_RANGE"));
    }

    @Test
    void onlyTheUsdToNgnCorridorIsSupported() {
        assertThatThrownBy(() -> fxApi.createQuote(UUID.randomUUID(), HUNDRED_DOLLARS, Currency.USD))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getCode()).isEqualTo("UNSUPPORTED_CORRIDOR"));
    }
}