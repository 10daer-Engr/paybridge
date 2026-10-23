package com.academy.paybridge.compliance;

import com.academy.paybridge.compliance.api.ComplianceApi;
import com.academy.paybridge.compliance.api.ComplianceDecision;
import com.academy.paybridge.compliance.api.ComplianceOutcome;
import com.academy.paybridge.compliance.api.ScreeningRequest;
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

@SpringBootTest(properties = {
        "paybridge.compliance.sender-daily-limit-usd=3000.00",
        "paybridge.compliance.recipient-daily-limit-usd=4500.00",
        "paybridge.compliance.partner-daily-limit-usd=5000.00"})
@ActiveProfiles("test")
class ComplianceServiceTest {

    @Autowired ComplianceApi complianceApi;
    @Autowired TransactionTemplate tx;
    @PersistenceContext EntityManager em;

    @Test
    void aCleanPaymentIsApprovedAndRecorded() {
        String subject = subject();

        ComplianceDecision decision = tx.execute(s -> complianceApi.screen(
                request(subject, UUID.randomUUID(), uniqueName(), "GB", account(), 10_000)));

        assertThat(decision.outcome()).isEqualTo(ComplianceOutcome.APPROVED);
        assertThat(decision.reasons()).isEmpty();
        assertThat(count(subject, "APPROVED")).isEqualTo(1);
    }

    @Test
    void aSenderMayReachTheLimitExactlyButNotPassIt() {
        String sender = uniqueName();

        assertThat(screen(UUID.randomUUID(), sender, "GB", account(), 200_000).outcome())
                .isEqualTo(ComplianceOutcome.APPROVED);                                // $2,000
        assertThat(screen(UUID.randomUUID(), sender, "GB", account(), 100_000).outcome())
                .isEqualTo(ComplianceOutcome.APPROVED);                                // $3,000 in total: allowed

        ComplianceDecision over = screen(UUID.randomUUID(), sender, "GB", account(), 1);   // one more cent
        assertThat(over.outcome()).isEqualTo(ComplianceOutcome.REJECTED);
        assertThat(over.reasons()).containsExactly("SENDER_DAILY_LIMIT_EXCEEDED");
        assertThat(over.publicMessage()).contains("daily limit");
    }

    @Test
    void aRecipientCannotReceiveMoreThanItsLimitFromManySenders() {
        String recipient = account();

        assertThat(screen(UUID.randomUUID(), uniqueName(), "GB", recipient, 200_000).outcome())
                .isEqualTo(ComplianceOutcome.APPROVED);
        assertThat(screen(UUID.randomUUID(), uniqueName(), "US", recipient, 200_000).outcome())
                .isEqualTo(ComplianceOutcome.APPROVED);                                // $4,000 so far

        ComplianceDecision third = screen(UUID.randomUUID(), uniqueName(), "CA", recipient, 200_000);

        assertThat(third.outcome()).isEqualTo(ComplianceOutcome.REJECTED);
        assertThat(third.reasons()).contains("RECIPIENT_DAILY_LIMIT_EXCEEDED");
    }

    @Test
    void aPartnerCannotSendMoreThanItsOwnLimit() {
        UUID partner = UUID.randomUUID();

        screen(partner, uniqueName(), "GB", account(), 200_000);
        screen(partner, uniqueName(), "GB", account(), 200_000);                       // $4,000 so far
        ComplianceDecision third = screen(partner, uniqueName(), "GB", account(), 200_000);

        assertThat(third.outcome()).isEqualTo(ComplianceOutcome.REJECTED);
        assertThat(third.reasons()).containsExactly("PARTNER_DAILY_LIMIT_EXCEEDED");
    }

    @Test
    void anExactWatchListMatchIsRejectedWithAMessageThatRevealsNothing() {
        ComplianceDecision decision = screen(UUID.randomUUID(), "IVAN  Sanctionov", "GB", account(), 10_000);

        assertThat(decision.outcome()).isEqualTo(ComplianceOutcome.REJECTED);
        assertThat(decision.reasons()).contains("SANCTIONS_MATCH");
        assertThat(decision.publicMessage()).isEqualTo("This payment cannot be processed.");
        assertThat(decision.publicMessage().toLowerCase()).doesNotContain("sanction", "list", "match");
    }

    @Test
    void aPossibleMatchIsReferredToAnAnalyst() {
        ComplianceDecision decision = screen(UUID.randomUUID(), "Ivan Sanctonov", "GB", account(), 10_000);

        assertThat(decision.outcome()).isEqualTo(ComplianceOutcome.REFERRED);
        assertThat(decision.reasons()).containsExactly("SANCTIONS_POSSIBLE_MATCH");
        assertThat(decision.publicMessage()).isEqualTo("This payment cannot be processed.");
    }

    @Test
    void aBlockedCountryIsRejected() {
        ComplianceDecision decision = screen(UUID.randomUUID(), uniqueName(), "KP", account(), 10_000);

        assertThat(decision.outcome()).isEqualTo(ComplianceOutcome.REJECTED);
        assertThat(decision.reasons()).containsExactly("SENDER_COUNTRY_BLOCKED");
        assertThat(decision.publicMessage()).isEqualTo("This payment cannot be processed.");
    }

    @Test
    void anApprovalIsUndoneWhenTheRestOfThePaymentFails() {
        String subject = subject();

        assertThatThrownBy(() -> tx.executeWithoutResult(s -> {
            complianceApi.screen(request(subject, UUID.randomUUID(), uniqueName(), "GB", account(), 10_000));
            throw new IllegalStateException("something later in the payment failed");
        })).isInstanceOf(IllegalStateException.class);

        // A payment that never happened must not use up anyone's limit.
        assertThat(count(subject, "APPROVED")).isZero();
    }

    @Test
    void aRefusalIsRememberedEvenThoughThePaymentIsRolledBack() {
        String subject = subject();

        assertThatThrownBy(() -> tx.executeWithoutResult(s -> {
            ComplianceDecision decision = complianceApi.screen(
                    request(subject, UUID.randomUUID(), "Ivan Sanctionov", "GB", account(), 10_000));
            throw new BusinessException("COMPLIANCE_REJECTED", decision.publicMessage());
        })).isInstanceOf(BusinessException.class);

        // The payment's transaction rolled back, but the audit record of the refusal did not.
        assertThat(count(subject, "REJECTED")).isEqualTo(1);
    }

    @Test
    void concurrentPaymentsCannotSlipPastASharedLimit() throws Exception {
        String sender = uniqueName();
        int threads = 4;   // kept well below the connection pool size: each refusal needs a second connection

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch startGate = new CountDownLatch(1);
        List<Future<ComplianceOutcome>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                startGate.await();
                return screen(UUID.randomUUID(), sender, "GB", account(), 200_000).outcome();   // $2,000 each
            }));
        }
        startGate.countDown();

        long approved = 0;
        for (Future<ComplianceOutcome> future : futures) {
            if (future.get(60, TimeUnit.SECONDS) == ComplianceOutcome.APPROVED) {
                approved++;
            }
        }
        pool.shutdown();

        assertThat(approved).isEqualTo(1);   // $3,000 allows one $2,000 payment, never two
    }

    @Test
    void aSanctionedRecipientNameIsRejectedWithTheSameVagueMessage() {
        ComplianceDecision decision = tx.execute(s -> complianceApi.screen(new ScreeningRequest(
                UUID.randomUUID(), subject(), uniqueName(), "GB", account(),
                new Money(10_000, Currency.USD), "ivan SANCTIONOV")));

        assertThat(decision.outcome()).isEqualTo(ComplianceOutcome.REJECTED);
        assertThat(decision.reasons()).contains("SANCTIONS_RECIPIENT_MATCH");
        assertThat(decision.publicMessage()).isEqualTo("This payment cannot be processed.");
    }

    @Test
    void anOrdinaryRecipientNameIsFine() {
        ComplianceDecision decision = tx.execute(s -> complianceApi.screen(new ScreeningRequest(
                UUID.randomUUID(), subject(), uniqueName(), "GB", account(),
                new Money(10_000, Currency.USD), "Ngozi Adeyemi")));

        assertThat(decision.outcome()).isEqualTo(ComplianceOutcome.APPROVED);
    }

    // ---- helpers ----

    private ComplianceDecision screen(UUID partner, String sender, String country, String recipient, long cents) {
        return tx.execute(s -> complianceApi.screen(request(subject(), partner, sender, country, recipient, cents)));
    }

    private static ScreeningRequest request(String subject, UUID partner, String sender, String country,
                                            String recipient, long cents) {
        return new ScreeningRequest(partner, subject, sender, country, recipient, new Money(cents, Currency.USD));
    }

    private static String subject() {
        return "ref-" + UUID.randomUUID();
    }

    private static String uniqueName() {
        return "Sender " + UUID.randomUUID();
    }

    private static String account() {
        return "acct-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private long count(String subject, String outcome) {
        Object result = em.createNativeQuery(
                        "select count(*) from compliance_check where subject_ref = ?1 and outcome = ?2")
                .setParameter(1, subject)
                .setParameter(2, outcome)
                .getSingleResult();
        return ((Number) result).longValue();
    }
}