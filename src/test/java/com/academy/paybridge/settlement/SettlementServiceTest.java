package com.academy.paybridge.settlement;

import com.academy.paybridge.account.api.AccountApi;
import com.academy.paybridge.account.api.AccountView;
import com.academy.paybridge.fx.api.FxApi;
import com.academy.paybridge.fx.api.QuoteView;
import com.academy.paybridge.partner.api.PartnerApi;
import com.academy.paybridge.partner.api.RegisteredPartner;
import com.academy.paybridge.remittance.api.CreateRemittanceCommand;
import com.academy.paybridge.remittance.api.RemittanceApi;
import com.academy.paybridge.remittance.api.RemittanceView;
import com.academy.paybridge.settlement.api.PaymentView;
import com.academy.paybridge.settlement.api.ReconciliationReport;
import com.academy.paybridge.settlement.api.RecordPaymentCommand;
import com.academy.paybridge.settlement.api.SettlementApi;
import com.academy.paybridge.settlement.api.SettlementDetail;
import com.academy.paybridge.settlement.api.SettlementView;
import com.academy.paybridge.settlement.api.StatementLine;
import com.academy.paybridge.shared.exception.BusinessException;
import com.academy.paybridge.shared.money.Currency;
import com.academy.paybridge.shared.money.Money;
import com.academy.paybridge.transfer.FakeGatewayConfig;
import com.academy.paybridge.transfer.FakeTransferGateway;
import com.academy.paybridge.transfer.FakeTransferGateway.Mode;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

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
@Import(FakeGatewayConfig.class)
class SettlementServiceTest {

    private static final String NOSTRO = "0000000007";

    @Autowired PartnerApi partnerApi;
    @Autowired AccountApi accountApi;
    @Autowired FxApi fxApi;
    @Autowired RemittanceApi remittanceApi;
    @Autowired SettlementApi settlementApi;
    @Autowired FakeTransferGateway fake;
    @PersistenceContext EntityManager em;

    private record Fixture(UUID partnerId, AccountView partnerAccount) {
    }

    @BeforeEach
    void resetFake() {
        fake.reset();
    }

    @Test
    void aBatchContainsOnlyCompletedRemittancesAndALateOneLandsInTheNextBatch() {
        Fixture f = fixture();
        RemittanceView a = payIntoAccount(f, 10_000, "INV-A");
        RemittanceView b = payIntoAccount(f, 20_000, "INV-B");
        RemittanceView c = payIntoAccount(f, 5_000, null);

        fake.setMode(Mode.REJECT);
        assertThat(bankPayout(f, 10_000).status()).isEqualTo("REVERSED");        // never enters a batch
        fake.setMode(Mode.TIMEOUT_BUT_PROCESSED);
        RemittanceView processing = bankPayout(f, 10_000);
        assertThat(processing.status()).isEqualTo("PROCESSING");                 // not final yet

        SettlementView batch = settlementApi.generate(f.partnerId());

        assertThat(batch.itemCount()).isEqualTo(3);
        assertThat(batch.gross().minorUnits()).isEqualTo(35_000);                 // $350.00 = $100 + $200 + $50
        assertThat(batch.fee().minorUnits()).isEqualTo(300);                      // three $1.00 fees
        assertThat(batch.converted().minorUnits()).isEqualTo(34_700);
        assertThat(batch.payout().minorUnits()).isEqualTo(
                a.receiveAmount().minorUnits() + b.receiveAmount().minorUnits() + c.receiveAmount().minorUnits());
        assertThat(batch.status()).isEqualTo("OPEN");
        assertThat(batch.reconciliationStatus()).isEqualTo("PENDING");
        assertThat(batch.outstanding().minorUnits()).isEqualTo(35_000);

        SettlementDetail detail = settlementApi.detail(f.partnerId(), batch.id());
        assertThat(detail.items()).extracting(i -> i.reference())
                .containsExactlyInAnyOrder("INV-A", "INV-B", c.id().toString());

        // Nothing left to claim until something else completes.
        assertThatThrownBy(() -> settlementApi.generate(f.partnerId()))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getCode()).isEqualTo("NOTHING_TO_SETTLE"));

        // The processing payout completes later: it joins the NEXT batch.
        assertThat(remittanceApi.refresh(processing.id(), f.partnerId()).status()).isEqualTo("COMPLETED");
        SettlementView next = settlementApi.generate(f.partnerId());
        assertThat(next.itemCount()).isEqualTo(1);
        assertThat(next.gross().minorUnits()).isEqualTo(10_000);
    }

    @Test
    void aPaymentIsPostedToTheLedgerAndRestoresThePartnersCredit() {
        Fixture f = fixture();
        payIntoAccount(f, 10_000, "INV-1");
        payIntoAccount(f, 20_000, "INV-2");
        SettlementView batch = settlementApi.generate(f.partnerId());
        long partnerBefore = balance(f.partnerAccount());          // -$300.00
        long nostroBefore = balance(NOSTRO);
        String key = key();

        PaymentView payment = settlementApi.recordPayment(pay(f, batch, key, 10_000, "WIRE-1"));

        assertThat(payment.replayed()).isFalse();
        assertThat(payment.settlement().paid().minorUnits()).isEqualTo(10_000);
        assertThat(payment.settlement().outstanding().minorUnits()).isEqualTo(20_000);
        assertThat(balance(f.partnerAccount()) - partnerBefore).isEqualTo(10_000);    // owes $100 less
        assertThat(balance(NOSTRO) - nostroBefore).isEqualTo(-10_000);                // our USD bank account was debited
        assertEveryCurrencyNetsToZero();

        // The same payment sent again is recognised, not applied twice.
        PaymentView replay = settlementApi.recordPayment(pay(f, batch, key, 10_000, "WIRE-1"));
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.settlement().paid().minorUnits()).isEqualTo(10_000);
        assertThat(balance(f.partnerAccount()) - partnerBefore).isEqualTo(10_000);

        // The same key for a different payment is refused.
        assertThatThrownBy(() -> settlementApi.recordPayment(pay(f, batch, key, 5_000, "WIRE-1")))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getCode()).isEqualTo("IDEMPOTENCY_CONFLICT"));

        // Paying more than is owed is refused.
        assertThatThrownBy(() -> settlementApi.recordPayment(pay(f, batch, key(), 20_001, "WIRE-2")))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getCode()).isEqualTo("OVERPAYMENT_NOT_ALLOWED"));
    }

    @Test
    void aBatchClosesOnlyWhenFullyPaidAndReconciled() {
        Fixture f = fixture();
        payIntoAccount(f, 10_000, "INV-1");
        SettlementView batch = settlementApi.generate(f.partnerId());

        SettlementView afterPayment = settlementApi.recordPayment(pay(f, batch, key(), 10_000, "WIRE-1")).settlement();
        assertThat(afterPayment.status()).isEqualTo("OPEN");                  // money alone is not enough

        ReconciliationReport report = settlementApi.reconcile(f.partnerId(), batch.id(),
                List.of(new StatementLine("INV-1", usd(10_000))));

        assertThat(report.status()).isEqualTo("MATCHED");
        assertThat(report.breaks()).isEmpty();
        assertThat(report.settlement().status()).isEqualTo("CLOSED");
        assertThat(report.settlement().closedAt()).isNotNull();
        assertThat(balance(f.partnerAccount())).isZero();                     // nothing owed any more

        // A closed settlement is frozen.
        assertThatThrownBy(() -> settlementApi.recordPayment(pay(f, batch, key(), 1, "WIRE-9")))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getCode()).isEqualTo("SETTLEMENT_CLOSED"));
        assertThatThrownBy(() -> settlementApi.reconcile(f.partnerId(), batch.id(), List.of()))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getCode()).isEqualTo("SETTLEMENT_CLOSED"));
    }

    @Test
    void breaksKeepABatchOpenEvenWhenFullyPaidUntilTheStatementIsCorrected() {
        Fixture f = fixture();
        payIntoAccount(f, 10_000, "INV-1");
        payIntoAccount(f, 20_000, "INV-2");
        payIntoAccount(f, 5_000, "INV-3");
        SettlementView batch = settlementApi.generate(f.partnerId());
        settlementApi.recordPayment(pay(f, batch, key(), 35_000, "WIRE-1"));    // fully paid

        ReconciliationReport bad = settlementApi.reconcile(f.partnerId(), batch.id(), List.of(
                new StatementLine("INV-1", usd(10_000)),
                new StatementLine("INV-2", usd(15_000)),      // we say $200.00
                new StatementLine("INV-4", usd(7_500))));     // we have no INV-4, and they omitted INV-3

        assertThat(bad.status()).isEqualTo("BREAKS");
        assertThat(bad.breaks()).extracting(b -> b.kind() + ":" + b.reference()).containsExactly(
                "MISSING_FROM_STATEMENT:INV-3",
                "UNEXPECTED_IN_STATEMENT:INV-4",
                "AMOUNT_MISMATCH:INV-2");
        assertThat(bad.statementTotal().minorUnits()).isEqualTo(32_500);
        assertThat(bad.ourTotal().minorUnits()).isEqualTo(35_000);
        assertThat(bad.settlement().status()).isEqualTo("OPEN");                // paid in full, but not agreed
        assertThat(bad.settlement().reconciliationStatus()).isEqualTo("BREAKS");

        ReconciliationReport good = settlementApi.reconcile(f.partnerId(), batch.id(), List.of(
                new StatementLine("INV-1", usd(10_000)),
                new StatementLine("INV-2", usd(20_000)),
                new StatementLine("INV-3", usd(5_000))));

        assertThat(good.status()).isEqualTo("MATCHED");
        assertThat(good.settlement().status()).isEqualTo("CLOSED");
        assertThat(reconciliationRuns(batch.id())).isEqualTo(2);                // both attempts are on record
    }

    @Test
    void theLedgerTheRemittancesAndThePaymentsAlwaysAgree() {
        Fixture f = fixture();
        payIntoAccount(f, 10_000, "INV-1");
        payIntoAccount(f, 20_000, "INV-2");
        fake.setMode(Mode.TIMEOUT_BUT_PROCESSED);
        bankPayout(f, 5_000);                                                  // PROCESSING: still owed, not yet settleable
        SettlementView batch = settlementApi.generate(f.partnerId());
        settlementApi.recordPayment(pay(f, batch, key(), 12_345, "WIRE-1"));

        long owedAccordingToTheLedger = -balance(f.partnerAccount());
        long completedAndProcessing = scalar(
                "select coalesce(sum(send_amount_minor), 0) from remittance "
                        + "where partner_id = ?1 and status in ('COMPLETED', 'PROCESSING')", f.partnerId());
        long paid = scalar(
                "select coalesce(sum(amount_usd_minor), 0) from settlement_payment where partner_id = ?1",
                f.partnerId());

        assertThat(owedAccordingToTheLedger).isEqualTo(completedAndProcessing - paid);
        assertThat(owedAccordingToTheLedger).isEqualTo(10_000 + 20_000 + 5_000 - 12_345);
        assertEveryCurrencyNetsToZero();
    }

    @Test
    void whenSeveralBatchRequestsRaceExactlyOneBatchIsCreated() throws Exception {
        Fixture f = fixture();
        payIntoAccount(f, 10_000, "INV-1");
        payIntoAccount(f, 20_000, "INV-2");
        payIntoAccount(f, 5_000, "INV-3");

        int threads = 4;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch startGate = new CountDownLatch(1);
        List<Future<Boolean>> results = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            results.add(pool.submit(() -> {
                startGate.await();
                try {
                    settlementApi.generate(f.partnerId());
                    return true;
                } catch (BusinessException e) {
                    return false;   // NOTHING_TO_SETTLE: another request already claimed everything
                }
            }));
        }
        startGate.countDown();

        long winners = 0;
        for (Future<Boolean> result : results) {
            if (result.get(60, TimeUnit.SECONDS)) {
                winners++;
            }
        }
        pool.shutdown();

        assertThat(winners).isEqualTo(1);
        assertThat(settlementApi.list(f.partnerId())).hasSize(1);
        assertThat(settlementApi.list(f.partnerId()).get(0).itemCount()).isEqualTo(3);
    }

    @Test
    void aSettlementBelongsToItsPartnerOnly() {
        Fixture owner = fixture();
        Fixture other = fixture();
        payIntoAccount(owner, 10_000, "INV-1");
        SettlementView batch = settlementApi.generate(owner.partnerId());

        assertThatThrownBy(() -> settlementApi.detail(other.partnerId(), batch.id()))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getCode()).isEqualTo("SETTLEMENT_NOT_FOUND"));
        assertThatThrownBy(() -> settlementApi.recordPayment(pay(other, batch, key(), 100, "WIRE-X")))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getCode()).isEqualTo("SETTLEMENT_NOT_FOUND"));
        assertThatThrownBy(() -> settlementApi.reconcile(other.partnerId(), batch.id(), List.of()))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getCode()).isEqualTo("SETTLEMENT_NOT_FOUND"));
        assertThat(settlementApi.list(other.partnerId())).isEmpty();
    }

    // ---- helpers ----

    private Fixture fixture() {
        RegisteredPartner partner = partnerApi.register("Test Partner " + UUID.randomUUID(), "ops@example.com");
        return new Fixture(partner.partnerId(), accountApi.partnerAccount(partner.partnerId(), Currency.USD));
    }

    private RemittanceView payIntoAccount(Fixture f, long cents, String partnerReference) {
        AccountView recipient = accountApi.openAccount(UUID.randomUUID(), Currency.NGN);
        QuoteView quote = fxApi.createQuote(f.partnerId(), new Money(cents, Currency.USD), Currency.NGN);
        RemittanceView view = remittanceApi.create(new CreateRemittanceCommand(
                f.partnerId(), key(), partnerReference, quote.id(),
                "Sender " + UUID.randomUUID(), "GB", recipient.accountNumber())).remittance();
        assertThat(view.status()).isEqualTo("COMPLETED");
        return view;
    }

    private RemittanceView bankPayout(Fixture f, long cents) {
        QuoteView quote = fxApi.createQuote(f.partnerId(), new Money(cents, Currency.USD), Currency.NGN);
        return remittanceApi.create(new CreateRemittanceCommand(
                f.partnerId(), key(), null, quote.id(),
                "Sender " + UUID.randomUUID(), "GB", "0123456789", "001")).remittance();
    }

    private static RecordPaymentCommand pay(Fixture f, SettlementView batch, String key, long cents, String wire) {
        return new RecordPaymentCommand(f.partnerId(), batch.id(), key, usd(cents), wire);
    }

    private static Money usd(long cents) {
        return new Money(cents, Currency.USD);
    }

    private static String key() {
        return "key-" + UUID.randomUUID();
    }

    private long balance(AccountView account) {
        return balance(account.accountNumber());
    }

    private long balance(String accountNumber) {
        return accountApi.getByAccountNumber(accountNumber).balance().minorUnits();
    }

    private long scalar(String sql, Object parameter) {
        Object result = em.createNativeQuery(sql).setParameter(1, parameter).getSingleResult();
        return ((Number) result).longValue();
    }

    private long reconciliationRuns(UUID batchId) {
        return scalar("select count(*) from settlement_reconciliation where batch_id = ?1", batchId);
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