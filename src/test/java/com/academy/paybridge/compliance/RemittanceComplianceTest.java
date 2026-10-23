package com.academy.paybridge.compliance;

import com.academy.paybridge.account.api.AccountApi;
import com.academy.paybridge.account.api.AccountView;
import com.academy.paybridge.fx.api.FxApi;
import com.academy.paybridge.fx.api.QuoteView;
import com.academy.paybridge.partner.api.PartnerApi;
import com.academy.paybridge.partner.api.RegisteredPartner;
import com.academy.paybridge.remittance.api.CreateRemittanceCommand;
import com.academy.paybridge.remittance.api.RemittanceApi;
import com.academy.paybridge.shared.exception.BusinessException;
import com.academy.paybridge.shared.money.Currency;
import com.academy.paybridge.shared.money.Money;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = {
        "paybridge.compliance.sender-daily-limit-usd=3000.00",
        "paybridge.compliance.recipient-daily-limit-usd=4500.00",
        "paybridge.compliance.partner-daily-limit-usd=5000.00"})
@ActiveProfiles("test")
class RemittanceComplianceTest {

    @Autowired PartnerApi partnerApi;
    @Autowired AccountApi accountApi;
    @Autowired FxApi fxApi;
    @Autowired RemittanceApi remittanceApi;

    private record Fixture(UUID partnerId, AccountView partnerAccount, AccountView recipient) {
    }

    @Test
    void aSanctionedSenderIsStoppedAndNoMoneyMoves() {
        Fixture f = fixture();
        QuoteView quote = quote(f, 10_000);

        assertThatThrownBy(() -> remittanceApi.create(command(f, quote, "Ivan Sanctionov", "GB")))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.getCode()).isEqualTo("COMPLIANCE_REJECTED");
                    assertThat(e.getMessage()).isEqualTo("This payment cannot be processed.");
                });

        assertNothingMoved(f, quote);
    }

    @Test
    void aPossibleMatchIsReferredAndNoMoneyMoves() {
        Fixture f = fixture();
        QuoteView quote = quote(f, 10_000);

        assertThatThrownBy(() -> remittanceApi.create(command(f, quote, "Ivan Sanctonov", "GB")))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getCode()).isEqualTo("COMPLIANCE_REFERRED"));

        assertNothingMoved(f, quote);
    }

    @Test
    void aBlockedCountryIsStopped() {
        Fixture f = fixture();
        QuoteView quote = quote(f, 10_000);

        assertThatThrownBy(() -> remittanceApi.create(command(f, quote, "Test Sender", "KP")))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getCode()).isEqualTo("COMPLIANCE_REJECTED"));

        assertNothingMoved(f, quote);
    }

    @Test
    void theSecondTwoThousandDollarPaymentFromTheSameSenderHitsTheDailyLimit() {
        Fixture f = fixture();
        String sender = "Sender " + UUID.randomUUID();

        assertThat(remittanceApi.create(command(f, quote(f, 200_000), sender, "GB")).remittance().status())
                .isEqualTo("COMPLETED");

        QuoteView second = quote(f, 200_000);
        assertThatThrownBy(() -> remittanceApi.create(command(f, second, sender, "GB")))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.getCode()).isEqualTo("COMPLIANCE_REJECTED");
                    assertThat(e.getMessage()).contains("daily limit");
                });

        assertThat(remittanceApi.recent(f.partnerId())).hasSize(1);
        assertThat(fxApi.getQuote(second.id(), f.partnerId()).status()).isEqualTo("ACTIVE");
    }

    // ---- helpers ----

    private void assertNothingMoved(Fixture f, QuoteView quote) {
        assertThat(remittanceApi.recent(f.partnerId())).isEmpty();
        assertThat(accountApi.getByAccountNumber(f.recipient().accountNumber()).balance().minorUnits()).isZero();
        assertThat(accountApi.getByAccountNumber(f.partnerAccount().accountNumber()).balance().minorUnits()).isZero();
        assertThat(fxApi.getQuote(quote.id(), f.partnerId()).status()).isEqualTo("ACTIVE");   // still usable
    }

    private Fixture fixture() {
        RegisteredPartner partner = partnerApi.register("Test Partner " + UUID.randomUUID(), "ops@example.com");
        AccountView partnerAccount = accountApi.partnerAccount(partner.partnerId(), Currency.USD);
        AccountView recipient = accountApi.openAccount(UUID.randomUUID(), Currency.NGN);
        return new Fixture(partner.partnerId(), partnerAccount, recipient);
    }

    private QuoteView quote(Fixture f, long cents) {
        return fxApi.createQuote(f.partnerId(), new Money(cents, Currency.USD), Currency.NGN);
    }

    private static CreateRemittanceCommand command(Fixture f, QuoteView quote, String sender, String country) {
        return new CreateRemittanceCommand(f.partnerId(), "key-" + UUID.randomUUID(), null,
                quote.id(), sender, country, f.recipient().accountNumber());
    }
}