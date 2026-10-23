package com.academy.paybridge.remittance;

import com.academy.paybridge.account.api.AccountApi;
import com.academy.paybridge.account.api.AccountView;
import com.academy.paybridge.fx.api.FxApi;
import com.academy.paybridge.fx.api.QuoteView;
import com.academy.paybridge.partner.api.PartnerApi;
import com.academy.paybridge.partner.api.RegisteredPartner;
import com.academy.paybridge.remittance.api.CreateRemittanceCommand;
import com.academy.paybridge.remittance.api.RemittanceApi;
import com.academy.paybridge.remittance.api.RemittanceView;
import com.academy.paybridge.remittance.service.RemittancePayoutEventHandler;
import com.academy.paybridge.remittance.service.StuckRemittanceReconciler;
import com.academy.paybridge.shared.exception.BusinessException;
import com.academy.paybridge.shared.money.Currency;
import com.academy.paybridge.shared.money.Money;
import com.academy.paybridge.transfer.FakeGatewayConfig;
import com.academy.paybridge.transfer.FakeTransferGateway;
import com.academy.paybridge.transfer.FakeTransferGateway.Mode;
import com.academy.paybridge.transfer.api.PayoutEvent;
import com.academy.paybridge.transfer.api.PayoutEventResult;
import com.academy.paybridge.transfer.api.PayoutStatus;
import com.academy.paybridge.transfer.service.PaystackWebhookService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
@Import(FakeGatewayConfig.class)
class RemittanceBankPayoutTest {

    // System account numbers
    private static final String CLEARING = "0000000002";
    private static final String FX_POSITION_USD = "0000000003";
    private static final String FX_POSITION_NGN = "0000000004";
    private static final String FEE_REVENUE_USD = "0000000005";
    private static final String FX_REVENUE_NGN = "0000000006";

    @Autowired PartnerApi partnerApi;
    @Autowired AccountApi accountApi;
    @Autowired FxApi fxApi;
    @Autowired RemittanceApi remittanceApi;
    @Autowired FakeTransferGateway fake;
    @Autowired StuckRemittanceReconciler reconciler;
    @Autowired RemittancePayoutEventHandler payoutHandler;
    @Autowired PaystackWebhookService webhookService;
    @PersistenceContext EntityManager em;

    private record Fixture(UUID partnerId, AccountView partnerAccount) {
    }

    private record Snapshot(long clearing, long fxPosUsd, long fxPosNgn, long feeRev, long fxRev) {
    }

    @BeforeEach
    void resetFake() {
        fake.reset();
    }

    @Test
    void aSuccessfulBankPayoutKeepsTheExchangeBooked() {
        Fixture f = fixture();
        Snapshot before = snapshot();
        QuoteView quote = quote(f, 10_000);

        RemittanceView view = remittanceApi.create(command(f, quote, key())).remittance();

        assertThat(view.status()).isEqualTo("COMPLETED");
        assertThat(view.recipientType()).isEqualTo("BANK");
        assertThat(view.recipientBankCode()).isEqualTo("001");
        assertThat(view.recipientName()).isEqualTo("TEST ACCOUNT HOLDER");
        assertThat(balance(f.partnerAccount())).isEqualTo(-10_000);                      // the partner owes $100
        assertThat(snapshot().clearing() - before.clearing()).isEqualTo(14_627_250);     // naira committed to the provider
        assertThat(fake.initiateCalls()).isEqualTo(1);
        assertEveryCurrencyNetsToZero();
    }

    @Test
    void aRejectedPayoutReversesTheWholeExchange() {
        Fixture f = fixture();
        Snapshot before = snapshot();
        QuoteView quote = quote(f, 10_000);
        fake.setMode(Mode.REJECT);

        RemittanceView view = remittanceApi.create(command(f, quote, key())).remittance();

        assertThat(view.status()).isEqualTo("REVERSED");
        assertThat(view.failureReason()).contains("rejected");
        assertThat(balance(f.partnerAccount())).isZero();          // the partner's credit is fully restored
        assertThat(snapshot()).isEqualTo(before);                  // clearing, positions and revenue all back to where they were
        assertThat(ledgerLegs(view.id())).isEqualTo(4);            // two booking legs and two reversal legs
        assertThat(fxApi.getQuote(quote.id(), f.partnerId()).status()).isEqualTo("USED");   // a retry needs a fresh quote
        assertEveryCurrencyNetsToZero();
    }

    @Test
    void aTimeoutLeavesTheExchangeBookedAndNeverReversesOnItsOwn() {
        Fixture f = fixture();
        QuoteView quote = quote(f, 10_000);
        fake.setMode(Mode.TIMEOUT_NOT_PROCESSED);

        RemittanceView view = remittanceApi.create(command(f, quote, key())).remittance();

        assertThat(view.status()).isEqualTo("PROCESSING");
        assertThat(balance(f.partnerAccount())).isEqualTo(-10_000);   // credit stays consumed while we wait
    }

    @Test
    void refreshingWhenTheProviderNeverGotTheRequestReversesTheExchange() {
        Fixture f = fixture();
        fake.setMode(Mode.TIMEOUT_NOT_PROCESSED);
        RemittanceView stuck = remittanceApi.create(command(f, quote(f, 10_000), key())).remittance();

        RemittanceView refreshed = remittanceApi.refresh(stuck.id(), f.partnerId());

        assertThat(refreshed.status()).isEqualTo("REVERSED");
        assertThat(balance(f.partnerAccount())).isZero();
        assertEveryCurrencyNetsToZero();
    }

    @Test
    void refreshingWhenTheProviderActuallyPaidCompletesWithoutReversing() {
        Fixture f = fixture();
        fake.setMode(Mode.TIMEOUT_BUT_PROCESSED);
        RemittanceView stuck = remittanceApi.create(command(f, quote(f, 10_000), key())).remittance();
        assertThat(stuck.status()).isEqualTo("PROCESSING");

        RemittanceView refreshed = remittanceApi.refresh(stuck.id(), f.partnerId());

        assertThat(refreshed.status()).isEqualTo("COMPLETED");
        assertThat(balance(f.partnerAccount())).isEqualTo(-10_000);   // the money did go out: no reversal
    }

    @Test
    void theReconciliationJobSettlesStuckPayoutsBothWays() {
        Fixture f = fixture();

        fake.setMode(Mode.TIMEOUT_BUT_PROCESSED);
        RemittanceView paid = remittanceApi.create(command(f, quote(f, 10_000), key())).remittance();
        fake.setMode(Mode.TIMEOUT_NOT_PROCESSED);
        RemittanceView lost = remittanceApi.create(command(f, quote(f, 10_000), key())).remittance();

        reconcileUntilSettled(paid.id(), f);
        reconcileUntilSettled(lost.id(), f);

        assertThat(remittanceApi.get(paid.id(), f.partnerId()).status()).isEqualTo("COMPLETED");
        assertThat(remittanceApi.get(lost.id(), f.partnerId()).status()).isEqualTo("REVERSED");
        assertThat(balance(f.partnerAccount())).isEqualTo(-10_000);    // only the paid one still counts
        assertEveryCurrencyNetsToZero();
    }

    @Test
    void aSuccessWebhookCompletesAPendingPayout() {
        Fixture f = fixture();
        fake.setMode(Mode.ACCEPT_PENDING);
        RemittanceView pending = remittanceApi.create(command(f, quote(f, 10_000), key())).remittance();
        assertThat(pending.status()).isEqualTo("PROCESSING");

        PayoutEventResult result = payoutHandler.handle(
                new PayoutEvent("rem_" + pending.id(), PayoutStatus.SUCCESS, "TRF_test", 14_627_250L));

        assertThat(result).isEqualTo(PayoutEventResult.APPLIED);
        assertThat(remittanceApi.get(pending.id(), f.partnerId()).status()).isEqualTo("COMPLETED");
    }

    @Test
    void aFailedWebhookThroughTheRealWebhookServiceReversesTheExchange() {
        Fixture f = fixture();
        fake.setMode(Mode.ACCEPT_PENDING);
        RemittanceView pending = remittanceApi.create(command(f, quote(f, 10_000), key())).remittance();

        String json = """
                {"event":"transfer.failed","data":{"reference":"rem_%s","amount":14627250,"currency":"NGN","status":"failed","transfer_code":"TRF_x"}}
                """.formatted(pending.id());
        PaystackWebhookService.Result result = webhookService.process(json.getBytes(StandardCharsets.UTF_8));

        assertThat(result).isEqualTo(PaystackWebhookService.Result.PROCESSED);
        assertThat(remittanceApi.get(pending.id(), f.partnerId()).status()).isEqualTo("REVERSED");
        assertThat(balance(f.partnerAccount())).isZero();
        assertEveryCurrencyNetsToZero();
    }

    @Test
    void duplicateAndContradictoryWebhooksChangeNothing() {
        Fixture f = fixture();
        fake.setMode(Mode.ACCEPT_PENDING);
        RemittanceView pending = remittanceApi.create(command(f, quote(f, 10_000), key())).remittance();
        String reference = "rem_" + pending.id();

        payoutHandler.handle(new PayoutEvent(reference, PayoutStatus.SUCCESS, "TRF_test", 14_627_250L));
        payoutHandler.handle(new PayoutEvent(reference, PayoutStatus.SUCCESS, "TRF_test", 14_627_250L));
        payoutHandler.handle(new PayoutEvent(reference, PayoutStatus.FAILED, "TRF_test", 14_627_250L));

        assertThat(remittanceApi.get(pending.id(), f.partnerId()).status()).isEqualTo("COMPLETED");
        assertThat(balance(f.partnerAccount())).isEqualTo(-10_000);   // a late "failed" is not acted on automatically
    }

    @Test
    void aWebhookWithTheWrongAmountOrAnUnknownReferenceIsRefused() {
        Fixture f = fixture();
        fake.setMode(Mode.ACCEPT_PENDING);
        RemittanceView pending = remittanceApi.create(command(f, quote(f, 10_000), key())).remittance();

        assertThat(payoutHandler.handle(new PayoutEvent("rem_" + pending.id(), PayoutStatus.SUCCESS, "TRF_test", 1L)))
                .isEqualTo(PayoutEventResult.AMOUNT_MISMATCH);
        assertThat(remittanceApi.get(pending.id(), f.partnerId()).status()).isEqualTo("PROCESSING");

        assertThat(payoutHandler.handle(new PayoutEvent("rem_" + UUID.randomUUID(), PayoutStatus.SUCCESS, null, null)))
                .isEqualTo(PayoutEventResult.UNKNOWN_REFERENCE);
    }

    @Test
    void aSanctionedBeneficiaryIsStoppedBeforeAnyMoneyMovesOrTheProviderIsAsked() {
        Fixture f = fixture();
        QuoteView quote = quote(f, 10_000);
        fake.setResolvedName("Ivan Sanctionov");

        assertThatThrownBy(() -> remittanceApi.create(command(f, quote, key())))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.getCode()).isEqualTo("COMPLIANCE_REJECTED");
                    assertThat(e.getMessage()).isEqualTo("This payment cannot be processed.");
                });

        assertThat(fake.initiateCalls()).isZero();
        assertThat(remittanceApi.recent(f.partnerId())).isEmpty();
        assertThat(balance(f.partnerAccount())).isZero();
        assertThat(fxApi.getQuote(quote.id(), f.partnerId()).status()).isEqualTo("ACTIVE");
    }

    @Test
    void theCreditLimitStopsABankPayoutBeforeTheProviderIsAsked() {
        Fixture f = fixture();   // credit limit: $10,000
        for (int i = 0; i < 5; i++) {
            remittanceApi.create(command(f, quote(f, 200_000), key()));
        }
        QuoteView sixth = quote(f, 200_000);

        assertThatThrownBy(() -> remittanceApi.create(command(f, sixth, key())))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getCode()).isEqualTo("CREDIT_LIMIT_EXCEEDED"));

        assertThat(fake.initiateCalls()).isEqualTo(5);                               // the sixth never reached the provider
        assertThat(fxApi.getQuote(sixth.id(), f.partnerId()).status()).isEqualTo("ACTIVE");
        assertThat(remittanceApi.recent(f.partnerId())).hasSize(5);
    }

    @Test
    void retryingABankRemittanceDoesNotPayTwice() {
        Fixture f = fixture();
        QuoteView quote = quote(f, 10_000);
        CreateRemittanceCommand command = command(f, quote, key());   // built once, sent twice

        var first = remittanceApi.create(command);
        var second = remittanceApi.create(command);

        assertThat(second.replayed()).isTrue();
        assertThat(second.remittance().id()).isEqualTo(first.remittance().id());
        assertThat(fake.initiateCalls()).isEqualTo(1);
        assertThat(balance(f.partnerAccount())).isEqualTo(-10_000);
    }

    // ---- helpers ----

    private Fixture fixture() {
        RegisteredPartner partner = partnerApi.register("Test Partner " + UUID.randomUUID(), "ops@example.com");
        return new Fixture(partner.partnerId(), accountApi.partnerAccount(partner.partnerId(), Currency.USD));
    }

    private QuoteView quote(Fixture f, long cents) {
        return fxApi.createQuote(f.partnerId(), new Money(cents, Currency.USD), Currency.NGN);
    }

    private static String key() {
        return "key-" + UUID.randomUUID();
    }

    private static CreateRemittanceCommand command(Fixture f, QuoteView quote, String key) {
        return new CreateRemittanceCommand(f.partnerId(), key, null, quote.id(),
                "Sender " + UUID.randomUUID(), "GB", "0123456789", "001");
    }

    private void reconcileUntilSettled(UUID remittanceId, Fixture f) {
        // Other tests may have left PROCESSING remittances behind, so loop until ours is reached.
        for (int i = 0; i < 10; i++) {
            reconciler.reconcileOnce();
            if (!"PROCESSING".equals(remittanceApi.get(remittanceId, f.partnerId()).status())) {
                return;
            }
        }
    }

    private Snapshot snapshot() {
        return new Snapshot(balance(CLEARING), balance(FX_POSITION_USD), balance(FX_POSITION_NGN),
                balance(FEE_REVENUE_USD), balance(FX_REVENUE_NGN));
    }

    private long balance(AccountView account) {
        return balance(account.accountNumber());
    }

    private long balance(String accountNumber) {
        return accountApi.getByAccountNumber(accountNumber).balance().minorUnits();
    }

    private long ledgerLegs(UUID remittanceId) {
        Object count = em.createNativeQuery("select count(*) from ledger_transaction where correlation_id = ?1")
                .setParameter(1, "REM:" + remittanceId)
                .getSingleResult();
        return ((Number) count).longValue();
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