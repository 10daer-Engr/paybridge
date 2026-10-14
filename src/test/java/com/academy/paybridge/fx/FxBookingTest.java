package com.academy.paybridge.fx;

import com.academy.paybridge.account.api.AccountApi;
import com.academy.paybridge.account.api.AccountView;
import com.academy.paybridge.fx.api.BookConversionCommand;
import com.academy.paybridge.fx.api.ConversionBooking;
import com.academy.paybridge.fx.api.FxApi;
import com.academy.paybridge.fx.api.QuoteView;
import com.academy.paybridge.partner.api.PartnerApi;
import com.academy.paybridge.partner.api.RegisteredPartner;
import com.academy.paybridge.shared.exception.BusinessException;
import com.academy.paybridge.shared.money.Currency;
import com.academy.paybridge.shared.money.Money;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

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
class FxBookingTest {

    // System account numbers from migration V11
    private static final String FX_POSITION_USD = "0000000003";
    private static final String FX_POSITION_NGN = "0000000004";
    private static final String FEE_REVENUE_USD = "0000000005";
    private static final String FX_REVENUE_NGN = "0000000006";

    @Autowired PartnerApi partnerApi;
    @Autowired AccountApi accountApi;
    @Autowired FxApi fxApi;
    @Autowired TransactionTemplate tx;
    @PersistenceContext EntityManager em;

    private record Setup(RegisteredPartner partner, AccountView partnerAccount, AccountView recipient) {
    }

    @Test
    void aBookedExchangeMovesTheRightAmountsOnBothSidesAndStaysBalanced() {
        Setup s = setup();
        long fxPosUsd = balance(FX_POSITION_USD);
        long fxPosNgn = balance(FX_POSITION_NGN);
        long feeRev = balance(FEE_REVENUE_USD);
        long fxRev = balance(FX_REVENUE_NGN);

        String reference = "REM-TEST-" + UUID.randomUUID();
        ConversionBooking booking = book(s, 10_000, reference);

        assertThat(balance(s.recipient())).isEqualTo(14_627_250);          // N146,272.50
        assertThat(balance(s.partnerAccount())).isEqualTo(-10_000);        // the partner owes $100.00
        assertThat(balance(FX_POSITION_USD) - fxPosUsd).isEqualTo(9_900);
        assertThat(balance(FEE_REVENUE_USD) - feeRev).isEqualTo(100);
        assertThat(balance(FX_POSITION_NGN) - fxPosNgn).isEqualTo(-14_850_000);
        assertThat(balance(FX_REVENUE_NGN) - fxRev).isEqualTo(222_750);
        assertThat(booking.quote().status()).isEqualTo("USED");

        assertEveryCurrencyNetsToZero();
        assertThat(ledgerLegsFor(reference)).isEqualTo(2);
    }

    @Test
    void thePartnerCanOweUpToItsCreditLimitAndNoMore() {
        Setup s = setup();   // credit limit: $10,000

        for (int i = 0; i < 5; i++) {
            book(s, 200_000, "REM-TEST-" + UUID.randomUUID());   // five times $2,000 = exactly $10,000
        }
        assertThat(balance(s.partnerAccount())).isEqualTo(-1_000_000);

        QuoteView sixth = fxApi.createQuote(s.partner().partnerId(), new Money(200_000, Currency.USD), Currency.NGN);
        assertThatThrownBy(() -> bookExisting(s, sixth, "REM-TEST-" + UUID.randomUUID()))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getCode()).isEqualTo("CREDIT_LIMIT_EXCEEDED"));

        // Everything rolled back, including the quote: it can still be used later.
        assertThat(balance(s.partnerAccount())).isEqualTo(-1_000_000);
        assertThat(fxApi.getQuote(sixth.id(), s.partner().partnerId()).status()).isEqualTo("ACTIVE");
        assertEveryCurrencyNetsToZero();
    }

    @Test
    void theCreditLimitHoldsEvenWhenManyExchangesRaceEachOther() throws Exception {
        Setup s = setup();
        int attempts = 8;
        List<QuoteView> quotes = new ArrayList<>();
        for (int i = 0; i < attempts; i++) {
            quotes.add(fxApi.createQuote(s.partner().partnerId(), new Money(200_000, Currency.USD), Currency.NGN));
        }

        ExecutorService pool = Executors.newFixedThreadPool(attempts);
        CountDownLatch startGate = new CountDownLatch(1);
        List<Future<Boolean>> results = new ArrayList<>();
        for (QuoteView quote : quotes) {
            results.add(pool.submit(() -> {
                startGate.await();
                try {
                    bookExisting(s, quote, "REM-TEST-" + UUID.randomUUID());
                    return true;
                } catch (BusinessException e) {
                    assertThat(e.getCode()).isEqualTo("CREDIT_LIMIT_EXCEEDED");
                    return false;
                }
            }));
        }
        startGate.countDown();

        long successes = 0;
        for (Future<Boolean> result : results) {
            if (result.get(60, TimeUnit.SECONDS)) {
                successes++;
            }
        }
        pool.shutdown();

        assertThat(successes).isEqualTo(5);                                         // $10,000 / $2,000
        assertThat(balance(s.partnerAccount())).isEqualTo(-1_000_000);
        assertThat(balance(s.recipient())).isEqualTo(5 * quotes.get(0).receiveAmount().minorUnits());
        assertEveryCurrencyNetsToZero();
    }

    @Test
    void aPartnersMeEndpointDataReflectsItsExposure() {
        Setup s = setup();
        book(s, 10_000, "REM-TEST-" + UUID.randomUUID());

        AccountView position = accountApi.partnerAccount(s.partner().partnerId(), Currency.USD);

        assertThat(position.balance().minorUnits()).isEqualTo(-10_000);
        assertThat(position.accountType()).isEqualTo("PARTNER");
    }

    // ---- helpers ----

    private Setup setup() {
        RegisteredPartner partner = partnerApi.register("Test Partner " + UUID.randomUUID(), "ops@example.com");
        AccountView partnerAccount = accountApi.partnerAccount(partner.partnerId(), Currency.USD);
        AccountView recipient = accountApi.openAccount(UUID.randomUUID(), Currency.NGN);
        return new Setup(partner, partnerAccount, recipient);
    }

    private ConversionBooking book(Setup s, long sendCents, String reference) {
        QuoteView quote = fxApi.createQuote(s.partner().partnerId(), new Money(sendCents, Currency.USD), Currency.NGN);
        return bookExisting(s, quote, reference);
    }

    private ConversionBooking bookExisting(Setup s, QuoteView quote, String reference) {
        return tx.execute(status -> fxApi.bookConversion(new BookConversionCommand(
                quote.id(), s.partner().partnerId(), s.partnerAccount().id(), s.recipient().id(), reference)));
    }

    private long balance(AccountView account) {
        return balance(account.accountNumber());
    }

    private long balance(String accountNumber) {
        return accountApi.getByAccountNumber(accountNumber).balance().minorUnits();
    }

    /** Dollars and naira must each balance on their own. Adding them together would be meaningless. */
    @SuppressWarnings("unchecked")
    private void assertEveryCurrencyNetsToZero() {
        List<Object[]> rows = em.createNativeQuery(
                "select currency, sum(balance_minor) from account group by currency").getResultList();
        assertThat(rows).isNotEmpty();
        for (Object[] row : rows) {
            assertThat(((Number) row[1]).longValue()).as("net balance of all %s accounts", row[0]).isZero();
        }
    }

    private long ledgerLegsFor(String correlationId) {
        Object count = em.createNativeQuery(
                        "select count(*) from ledger_transaction where correlation_id = ?1")
                .setParameter(1, correlationId)
                .getSingleResult();
        return ((Number) count).longValue();
    }
}