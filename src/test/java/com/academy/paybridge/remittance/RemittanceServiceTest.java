package com.academy.paybridge.remittance;

import com.academy.paybridge.account.api.AccountApi;
import com.academy.paybridge.account.api.AccountView;
import com.academy.paybridge.fx.api.FxApi;
import com.academy.paybridge.fx.api.QuoteView;
import com.academy.paybridge.partner.api.PartnerApi;
import com.academy.paybridge.partner.api.RegisteredPartner;
import com.academy.paybridge.remittance.api.CreateRemittanceCommand;
import com.academy.paybridge.remittance.api.RemittanceApi;
import com.academy.paybridge.remittance.api.RemittanceResult;
import com.academy.paybridge.remittance.api.RemittanceView;
import com.academy.paybridge.shared.exception.BusinessException;
import com.academy.paybridge.shared.money.Currency;
import com.academy.paybridge.shared.money.Money;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
class RemittanceServiceTest {

    @Autowired PartnerApi partnerApi;
    @Autowired AccountApi accountApi;
    @Autowired FxApi fxApi;
    @Autowired RemittanceApi remittanceApi;
    @PersistenceContext EntityManager em;

    private record Fixture(UUID partnerId, AccountView partnerAccount, AccountView recipient) {
    }

    @Test
    void aRemittanceDeliversNairaAndRecordsWhatTheQuoteSaid() {
        Fixture f = fixture();
        QuoteView quote = quote(f, 10_000);

        RemittanceResult result = remittanceApi.create(command(f, quote, key()));
        RemittanceView view = result.remittance();

        assertThat(result.replayed()).isFalse();
        assertThat(view.status()).isEqualTo("COMPLETED");
        assertThat(view.sendAmount().minorUnits()).isEqualTo(10_000);
        assertThat(view.fee().minorUnits()).isEqualTo(100);
        assertThat(view.receiveAmount().minorUnits()).isEqualTo(14_627_250);
        assertThat(view.customerRate()).isEqualByComparingTo("1477.5");
        assertThat(view.completedAt()).isNotNull();

        assertThat(balance(f.recipient())).isEqualTo(14_627_250);   // the recipient got the naira
        assertThat(balance(f.partnerAccount())).isEqualTo(-10_000); // the partner owes $100.00
        assertThat(fxApi.getQuote(quote.id(), f.partnerId()).status()).isEqualTo("USED");
        assertEveryCurrencyNetsToZero();
    }

    @Test
    void retryingWithTheSameKeyReturnsTheOriginalAndMovesNothingTwice() {
        Fixture f = fixture();
        QuoteView quote = quote(f, 10_000);
        String key = key();

        RemittanceResult first = remittanceApi.create(command(f, quote, key));
        RemittanceResult second = remittanceApi.create(command(f, quote, key));

        assertThat(second.replayed()).isTrue();
        assertThat(second.remittance().id()).isEqualTo(first.remittance().id());
        assertThat(balance(f.partnerAccount())).isEqualTo(-10_000);
        assertThat(balance(f.recipient())).isEqualTo(14_627_250);
    }

    @Test
    void reusingAKeyForADifferentRemittanceIsRefused() {
        Fixture f = fixture();
        String key = key();
        remittanceApi.create(command(f, quote(f, 10_000), key));

        assertThatThrownBy(() -> remittanceApi.create(command(f, quote(f, 20_000), key)))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getCode()).isEqualTo("IDEMPOTENCY_CONFLICT"));
    }

    @Test
    void aQuoteCannotFundTwoRemittances() {
        Fixture f = fixture();
        QuoteView quote = quote(f, 10_000);
        remittanceApi.create(command(f, quote, key()));

        assertThatThrownBy(() -> remittanceApi.create(command(f, quote, key())))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getCode()).isEqualTo("QUOTE_ALREADY_USED"));

        assertThat(remittanceApi.recent(f.partnerId())).hasSize(1);
        assertThat(balance(f.partnerAccount())).isEqualTo(-10_000);
    }

    @Test
    void aPartnerCannotUseSomeoneElsesQuote() {
        Fixture owner = fixture();
        Fixture other = fixture();
        QuoteView ownersQuote = quote(owner, 10_000);

        assertThatThrownBy(() -> remittanceApi.create(command(other, ownersQuote, key())))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getCode()).isEqualTo("QUOTE_NOT_FOUND"));

        assertThat(fxApi.getQuote(ownersQuote.id(), owner.partnerId()).status()).isEqualTo("ACTIVE");
    }

    @Test
    void theCreditLimitStopsTheSixthTwoThousandDollarRemittanceAndKeepsTheQuote() {
        Fixture f = fixture();   // credit limit $10,000
        for (int i = 0; i < 5; i++) {
            remittanceApi.create(command(f, quote(f, 200_000), key()));
        }
        QuoteView sixth = quote(f, 200_000);

        assertThatThrownBy(() -> remittanceApi.create(command(f, sixth, key())))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getCode()).isEqualTo("CREDIT_LIMIT_EXCEEDED"));

        assertThat(remittanceApi.recent(f.partnerId())).hasSize(5);                 // nothing recorded
        assertThat(fxApi.getQuote(sixth.id(), f.partnerId()).status()).isEqualTo("ACTIVE");
        assertThat(balance(f.partnerAccount())).isEqualTo(-1_000_000);
        assertEveryCurrencyNetsToZero();
    }

    @Test
    void onlyARealCustomerNairaAccountCanReceive() {
        Fixture f = fixture();
        QuoteView quote = quote(f, 10_000);
        AccountView dollarAccount = accountApi.openAccount(UUID.randomUUID(), Currency.USD);

        // Unknown account number
        assertThatThrownBy(() -> remittanceApi.create(commandTo(f, quote, key(), "9999999999")))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getCode()).isEqualTo("ACCOUNT_NOT_FOUND"));
        // A USD account
        assertThatThrownBy(() -> remittanceApi.create(commandTo(f, quote, key(), dollarAccount.accountNumber())))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getCode()).isEqualTo("RECIPIENT_NOT_ELIGIBLE"));
        // A PayBridge system account
        assertThatThrownBy(() -> remittanceApi.create(commandTo(f, quote, key(), "0000000004")))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getCode()).isEqualTo("RECIPIENT_NOT_ELIGIBLE"));
        // The partner's own position account
        assertThatThrownBy(() -> remittanceApi.create(
                commandTo(f, quote, key(), f.partnerAccount().accountNumber())))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getCode()).isEqualTo("RECIPIENT_NOT_ELIGIBLE"));

        assertThat(fxApi.getQuote(quote.id(), f.partnerId()).status()).isEqualTo("ACTIVE");
    }

    @Test
    void aPartnerReferenceCannotBeUsedTwice() {
        Fixture f = fixture();
        String reference = "INV-" + UUID.randomUUID();
        remittanceApi.create(new CreateRemittanceCommand(f.partnerId(), key(), reference,
                quote(f, 10_000).id(), "John Smith", "GB", f.recipient().accountNumber()));

        assertThatThrownBy(() -> remittanceApi.create(new CreateRemittanceCommand(f.partnerId(), key(), reference,
                quote(f, 10_000).id(), "John Smith", "GB", f.recipient().accountNumber())))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getCode()).isEqualTo("DUPLICATE_PARTNER_REFERENCE"));
    }

    @Test
    void whenManyRequestsRaceForOneQuoteExactlyOneWins() throws Exception {
        Fixture f = fixture();
        QuoteView quote = quote(f, 10_000);

        List<Boolean> outcomes = runConcurrently(6, () -> {
            try {
                remittanceApi.create(command(f, quote, key()));
                return true;
            } catch (RuntimeException e) {
                return false;   // a clean business error, or a unique-constraint conflict
            }
        });

        assertThat(outcomes.stream().filter(b -> b).count()).isEqualTo(1);
        assertThat(remittanceApi.recent(f.partnerId())).hasSize(1);
        assertThat(balance(f.partnerAccount())).isEqualTo(-10_000);        // exactly one exchange booked
        assertThat(balance(f.recipient())).isEqualTo(14_627_250);
        assertEveryCurrencyNetsToZero();
    }

    @Test
    void whenTheSameRequestIsSentManyTimesAtOnceMoneyMovesOnce() throws Exception {
        Fixture f = fixture();
        QuoteView quote = quote(f, 10_000);
        CreateRemittanceCommand command = command(f, quote, key());

        List<Boolean> outcomes = runConcurrently(5, () -> {
            try {
                remittanceApi.create(command);
                return true;
            } catch (RuntimeException e) {
                return false;
            }
        });

        assertThat(outcomes).contains(true);
        assertThat(remittanceApi.recent(f.partnerId())).hasSize(1);
        assertThat(balance(f.partnerAccount())).isEqualTo(-10_000);
        assertEveryCurrencyNetsToZero();
    }

    // ---- helpers ----

    private Fixture fixture() {
        RegisteredPartner partner = partnerApi.register("Test Partner " + UUID.randomUUID(), "ops@example.com");
        AccountView partnerAccount = accountApi.partnerAccount(partner.partnerId(), Currency.USD);
        AccountView recipient = accountApi.openAccount(UUID.randomUUID(), Currency.NGN);
        return new Fixture(partner.partnerId(), partnerAccount, recipient);
    }

    private QuoteView quote(Fixture f, long cents) {
        return fxApi.createQuote(f.partnerId(), new Money(cents, Currency.USD), Currency.NGN);
    }

    private static String key() {
        return "key-" + UUID.randomUUID();
    }

    private static CreateRemittanceCommand command(Fixture f, QuoteView quote, String key) {
        return commandTo(f, quote, key, f.recipient().accountNumber());
    }

    private static CreateRemittanceCommand commandTo(Fixture f, QuoteView quote, String key, String accountNumber) {
        return new CreateRemittanceCommand(f.partnerId(), key, null, quote.id(), "John Smith", "GB", accountNumber);
    }

    private long balance(AccountView account) {
        return accountApi.getByAccountNumber(account.accountNumber()).balance().minorUnits();
    }

    private List<Boolean> runConcurrently(int threads, Callable<Boolean> task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch startGate = new CountDownLatch(1);
        List<Future<Boolean>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                startGate.await();
                return task.call();
            }));
        }
        startGate.countDown();

        List<Boolean> outcomes = new ArrayList<>();
        for (Future<Boolean> future : futures) {
            outcomes.add(future.get(60, TimeUnit.SECONDS));
        }
        pool.shutdown();
        return outcomes;
    }

    @SuppressWarnings("unchecked")
    private void assertEveryCurrencyNetsToZero() {
        List<Object[]> rows = em.createNativeQuery(
                "select currency, sum(balance_minor) from account group by currency").getResultList();
        for (Object[] row : rows) {
            assertThat(((Number) row[1]).longValue()).as("net balance of all %s accounts", row[0]).isZero();
        }
    }
}