package com.academy.paybridge.transfer;

import com.academy.paybridge.account.api.AccountApi;
import com.academy.paybridge.account.api.AccountView;
import com.academy.paybridge.account.api.SystemAccounts;
import com.academy.paybridge.fx.api.FxApi;
import com.academy.paybridge.fx.api.QuoteView;
import com.academy.paybridge.ledger.api.LedgerApi;
import com.academy.paybridge.ledger.api.LedgerTransactionType;
import com.academy.paybridge.ledger.api.PostingLine;
import com.academy.paybridge.ledger.api.PostingRequest;
import com.academy.paybridge.partner.api.PartnerApi;
import com.academy.paybridge.partner.api.RegisteredPartner;
import com.academy.paybridge.remittance.api.CreateRemittanceCommand;
import com.academy.paybridge.remittance.api.RemittanceApi;
import com.academy.paybridge.remittance.api.RemittanceView;
import com.academy.paybridge.shared.exception.BusinessException;
import com.academy.paybridge.shared.money.Currency;
import com.academy.paybridge.shared.money.Money;
import com.academy.paybridge.transfer.api.ExternalTransferApi;
import com.academy.paybridge.transfer.api.ExternalTransferCommand;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = {
        "paybridge.provider.mode=simulated",
        "paybridge.provider.simulated-pending-seconds=0"})
@ActiveProfiles("test")
class SimulatedProviderFlowTest {

    @Autowired PartnerApi partnerApi;
    @Autowired AccountApi accountApi;
    @Autowired FxApi fxApi;
    @Autowired RemittanceApi remittanceApi;
    @Autowired ExternalTransferApi externalTransferApi;
    @Autowired LedgerApi ledgerApi;

    private record Fixture(UUID partnerId, AccountView partnerAccount) {
    }

    @Test
    void anOrdinaryAccountIsPaidAtOnce() {
        Fixture f = fixture();

        RemittanceView view = send(f, "0123456789");

        assertThat(view.status()).isEqualTo("COMPLETED");
        assertThat(view.recipientName()).isEqualTo("SIMULATED ACCOUNT 0123456789");
        assertThat(balance(f.partnerAccount())).isEqualTo(-10_000);
    }

    @Test
    void accountsEndingInElevenAreRefusedAndTheExchangeIsReversed() {
        Fixture f = fixture();

        RemittanceView view = send(f, "0123456711");

        assertThat(view.status()).isEqualTo("REVERSED");
        assertThat(view.failureReason()).contains("refused");
        assertThat(balance(f.partnerAccount())).isZero();
    }

    @Test
    void accountsEndingInTwentyTwoTimeOutAndAreLaterReversedBecauseTheProviderNeverGotThem() {
        Fixture f = fixture();

        RemittanceView stuck = send(f, "0123456722");
        assertThat(stuck.status()).isEqualTo("PROCESSING");
        assertThat(balance(f.partnerAccount())).isEqualTo(-10_000);       // credit stays held while we wait

        RemittanceView refreshed = remittanceApi.refresh(stuck.id(), f.partnerId());

        assertThat(refreshed.status()).isEqualTo("REVERSED");
        assertThat(balance(f.partnerAccount())).isZero();
    }

    @Test
    void accountsEndingInThirtyThreeTimeOutButWerePaidSoNothingIsReversed() {
        Fixture f = fixture();

        RemittanceView stuck = send(f, "0123456733");
        assertThat(stuck.status()).isEqualTo("PROCESSING");

        RemittanceView refreshed = remittanceApi.refresh(stuck.id(), f.partnerId());

        assertThat(refreshed.status()).isEqualTo("COMPLETED");
        assertThat(balance(f.partnerAccount())).isEqualTo(-10_000);
    }

    @Test
    void accountsEndingInFortyFourStayPendingThenSucceed() {
        Fixture f = fixture();

        RemittanceView pending = send(f, "0123456744");
        assertThat(pending.status()).isEqualTo("PROCESSING");

        assertThat(remittanceApi.refresh(pending.id(), f.partnerId()).status()).isEqualTo("COMPLETED");
    }

    @Test
    void accountsEndingInFiftyFiveStayPendingThenFailAndAreReversed() {
        Fixture f = fixture();

        RemittanceView pending = send(f, "0123456755");
        assertThat(pending.status()).isEqualTo("PROCESSING");

        assertThat(remittanceApi.refresh(pending.id(), f.partnerId()).status()).isEqualTo("REVERSED");
        assertThat(balance(f.partnerAccount())).isZero();
    }

    @Test
    void aBeneficiaryNamedOnTheWatchListIsStoppedBeforeAnyMoneyMoves() {
        Fixture f = fixture();
        QuoteView quote = quote(f);

        assertThatThrownBy(() -> remittanceApi.create(command(f, quote, "0123456799")))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getCode()).isEqualTo("COMPLIANCE_REJECTED"));

        assertThat(balance(f.partnerAccount())).isZero();
        assertThat(remittanceApi.recent(f.partnerId())).isEmpty();
    }

    @Test
    void aCustomersBankTransferToARefusedAccountIsRefundedInFull() {
        AccountView customer = accountApi.openAccount(UUID.randomUUID(), Currency.NGN);
        Money funding = new Money(100_000, Currency.NGN);
        ledgerApi.post(new PostingRequest("TEST-FUND:" + UUID.randomUUID(), LedgerTransactionType.FUNDING,
                "test funding", List.of(PostingLine.debit(SystemAccounts.SANDBOX_FUNDING_NGN, funding),
                PostingLine.credit(customer.id(), funding))));

        var result = externalTransferApi.transferExternal(new ExternalTransferCommand(
                "key-" + UUID.randomUUID(), customer.accountNumber(), "0123456711", "058",
                new BigDecimal("500.00"), "simulated refusal"));

        assertThat(result.transfer().status()).isEqualTo("REVERSED");
        assertThat(balance(customer)).isEqualTo(100_000);        // fully refunded
    }

    // ---- helpers ----

    private Fixture fixture() {
        RegisteredPartner partner = partnerApi.register("Test Partner " + UUID.randomUUID(), "ops@example.com");
        return new Fixture(partner.partnerId(), accountApi.partnerAccount(partner.partnerId(), Currency.USD));
    }

    private QuoteView quote(Fixture f) {
        return fxApi.createQuote(f.partnerId(), new Money(10_000, Currency.USD), Currency.NGN);
    }

    private RemittanceView send(Fixture f, String bankAccount) {
        return remittanceApi.create(command(f, quote(f), bankAccount)).remittance();
    }

    private static CreateRemittanceCommand command(Fixture f, QuoteView quote, String bankAccount) {
        return new CreateRemittanceCommand(f.partnerId(), "key-" + UUID.randomUUID(), null, quote.id(),
                "Sender " + UUID.randomUUID(), "GB", bankAccount, "058");
    }

    private long balance(AccountView account) {
        return accountApi.getByAccountNumber(account.accountNumber()).balance().minorUnits();
    }
}