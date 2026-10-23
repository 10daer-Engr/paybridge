package com.academy.paybridge.remittance.service;

import com.academy.paybridge.account.api.AccountApi;
import com.academy.paybridge.account.api.AccountView;
import com.academy.paybridge.account.api.SystemAccounts;
import com.academy.paybridge.compliance.api.ComplianceApi;
import com.academy.paybridge.compliance.api.ComplianceDecision;
import com.academy.paybridge.compliance.api.ComplianceOutcome;
import com.academy.paybridge.compliance.api.ScreeningRequest;
import com.academy.paybridge.fx.api.BookConversionCommand;
import com.academy.paybridge.fx.api.ConversionBooking;
import com.academy.paybridge.fx.api.ConversionReversal;
import com.academy.paybridge.fx.api.FxApi;
import com.academy.paybridge.fx.api.QuoteView;
import com.academy.paybridge.fx.api.ReverseConversionCommand;
import com.academy.paybridge.remittance.api.CreateRemittanceCommand;
import com.academy.paybridge.remittance.api.RemittanceApi;
import com.academy.paybridge.remittance.api.RemittanceResult;
import com.academy.paybridge.remittance.api.RemittanceView;
import com.academy.paybridge.remittance.domain.Remittance;
import com.academy.paybridge.remittance.domain.RemittanceStatus;
import com.academy.paybridge.remittance.domain.RecipientType;
import com.academy.paybridge.remittance.repository.RemittanceRepository;
import com.academy.paybridge.shared.exception.BusinessException;
import com.academy.paybridge.shared.money.Currency;
import com.academy.paybridge.transfer.api.PayoutApi;
import com.academy.paybridge.transfer.api.PayoutEvent;
import com.academy.paybridge.transfer.api.PayoutEventResult;
import com.academy.paybridge.transfer.api.PayoutRequest;
import com.academy.paybridge.transfer.api.PayoutResult;
import com.academy.paybridge.transfer.api.PayoutStatus;
import com.academy.paybridge.transfer.api.PreparedRecipient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import com.academy.paybridge.remittance.api.RemittanceCompletedEvent;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Service
public class RemittanceService implements RemittanceApi {

    private static final Logger log = LoggerFactory.getLogger(RemittanceService.class);

    private final RemittanceRepository remittanceRepository;
    private final AccountApi accountApi;
    private final FxApi fxApi;
    private final ComplianceApi complianceApi;
    private final PayoutApi payoutApi;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final ApplicationEventPublisher events;
    private final Duration providerGrace;

    public RemittanceService(RemittanceRepository remittanceRepository,
                             AccountApi accountApi,
                             FxApi fxApi,
                             ComplianceApi complianceApi,
                             PayoutApi payoutApi,
                             TransactionTemplate transactionTemplate,
                             ApplicationEventPublisher events,
                             Clock clock,
                             @Value("${paybridge.transfers.provider-grace:2m}") Duration providerGrace) {
        this.remittanceRepository = remittanceRepository;
        this.accountApi = accountApi;
        this.fxApi = fxApi;
        this.complianceApi = complianceApi;
        this.payoutApi = payoutApi;
        this.tx = transactionTemplate;
        this.events = events;
        this.clock = clock;
        this.providerGrace = providerGrace;
    }

    // NOT @Transactional: the bank path needs to work outside a transaction while it waits on the provider.
    @Override
    public RemittanceResult create(CreateRemittanceCommand command) {
        Optional<Remittance> existing = remittanceRepository.findByIdempotencyKey(command.idempotencyKey());
        if (existing.isPresent()) {
            return replay(existing.get(), command);
        }

        if (command.partnerReference() != null
                && remittanceRepository.existsByPartnerIdAndPartnerReference(
                command.partnerId(), command.partnerReference())) {
            throw new BusinessException("DUPLICATE_PARTNER_REFERENCE",
                    "You already submitted a remittance with reference " + command.partnerReference());
        }

        return command.isBankPayout()
                ? createToBank(command)
                : tx.execute(status -> createToAccount(command));
    }

    // ---- delivery into a PayBridge account: one transaction, instant ----

    private RemittanceResult createToAccount(CreateRemittanceCommand command) {
        AccountView recipient = accountApi.getByAccountNumber(command.recipientAccountNumber());
        requireDeliverable(recipient);
        AccountView partnerAccount = accountApi.partnerAccount(command.partnerId(), Currency.USD);
        QuoteView quote = requireUsableQuote(command);

        screen(command, recipient.accountNumber(), null, quote);

        Instant now = clock.instant();
        Remittance remittance = remittanceRepository.saveAndFlush(Remittance.receiveToAccount(
                command.partnerId(), command.idempotencyKey(), command.partnerReference(),
                quote.id(), command.senderName(), command.senderCountry(),
                quote.sendAmount(), quote.fee(), quote.receiveAmount(), quote.customerRate(),
                recipient.id(), recipient.accountNumber(), now));

        ConversionBooking booking = fxApi.bookConversion(new BookConversionCommand(
                quote.id(), command.partnerId(), partnerAccount.id(), recipient.id(),
                "REM:" + remittance.getId()));

        remittance.markCompleted(booking.usdLedgerTransactionId(), booking.ngnLedgerTransactionId(),
                clock.instant());

        publishCompleted(remittance);
        return new RemittanceResult(toView(remittance), false);
    }

    // ---- delivery to a bank account: four phases ----

    private RemittanceResult createToBank(CreateRemittanceCommand command) {
        // Phase 1: pre-flight. No transaction, no money has moved.
        AccountView partnerAccount = accountApi.partnerAccount(command.partnerId(), Currency.USD);
        QuoteView quote = requireUsableQuote(command);
        PreparedRecipient recipient = payoutApi.prepareRecipient(
                command.recipientAccountNumber(), command.recipientBankCode());

        // Phase 2: screen, record, book the exchange and commit. The naira now sits in the clearing account.
        Remittance held = tx.execute(status -> holdForBank(command, quote, partnerAccount, recipient));

        // Phase 3: ask the provider to pay. NO transaction is open.
        PayoutResult result = payoutApi.initiate(new PayoutRequest(
                held.getProviderReference(), recipient.recipientCode(),
                held.getReceiveAmount(), "Remittance " + held.getId()));

        if (result.status() == PayoutStatus.UNKNOWN) {
            // We do NOT know whether the provider paid. Do not reverse. Leave it PROCESSING.
            log.warn("Outcome unknown for remittance {}; leaving it PROCESSING until the provider is asked",
                    held.getId());
            return new RemittanceResult(toView(held), false);
        }

        // Phase 4: apply the outcome.
        Remittance settled = settle(held.getId(), outcomeOf(result.status(), result.providerCode(), result.message()));
        return new RemittanceResult(toView(settled), false);
    }

    private Remittance holdForBank(CreateRemittanceCommand command, QuoteView quote,
                                   AccountView partnerAccount, PreparedRecipient recipient) {
        screen(command, command.recipientBankCode() + ":" + command.recipientAccountNumber(),
                recipient.accountName(), quote);

        Instant now = clock.instant();
        Remittance remittance = remittanceRepository.saveAndFlush(Remittance.receiveToBank(
                command.partnerId(), command.idempotencyKey(), command.partnerReference(),
                quote.id(), command.senderName(), command.senderCountry(),
                quote.sendAmount(), quote.fee(), quote.receiveAmount(), quote.customerRate(),
                command.recipientAccountNumber(), command.recipientBankCode(),
                recipient.accountName(), recipient.recipientCode(), now));

        // The NGN leg credits the CLEARING account: naira committed to the provider, not yet confirmed.
        ConversionBooking booking = fxApi.bookConversion(new BookConversionCommand(
                quote.id(), command.partnerId(), partnerAccount.id(),
                SystemAccounts.PAYSTACK_CLEARING_NGN, "REM:" + remittance.getId()));

        // Lowercase letters, digits, '-' and '_' only: the format the provider accepts. "rem_" routes webhooks here.
        remittance.markProcessing(booking.usdLedgerTransactionId(), booking.ngnLedgerTransactionId(),
                "rem_" + remittance.getId(), clock.instant());
        return remittance;
    }

    // ---- settlement: idempotent, and safe to call from any number of places ----

    private Remittance settle(UUID remittanceId, Outcome outcome) {
        return tx.execute(status -> applyOutcome(remittanceId, outcome));
    }

    private Remittance applyOutcome(UUID remittanceId, Outcome outcome) {
        Remittance remittance = remittanceRepository.findByIdForUpdate(remittanceId)
                .orElseThrow(() -> new BusinessException(
                        "REMITTANCE_NOT_FOUND", "No remittance with id " + remittanceId));

        if (remittance.getStatus() != RemittanceStatus.PROCESSING) {
            if (outcome.kind() == Outcome.Kind.REVERSE && remittance.getStatus() == RemittanceStatus.COMPLETED) {
                log.error("Provider reports remittance {} as failed or reversed, but it is already COMPLETED. "
                        + "Manual review needed; no automatic reversal.", remittance.getId());
            }
            return remittance;   // somebody else already settled it
        }

        if (outcome.providerCode() != null) {
            remittance.recordProviderTransferCode(outcome.providerCode());
        }

        Instant now = clock.instant();
        switch (outcome.kind()) {
            case COMPLETED -> {
                remittance.confirmDelivered(now);
                publishCompleted(remittance);
            }
            case REVERSE -> reverseExchange(remittance, outcome.reason(), now);
            case STILL_PROCESSING -> { }
        }
        return remittance;
    }

    private void reverseExchange(Remittance remittance, String reason, Instant now) {
        AccountView partnerAccount = accountApi.partnerAccount(remittance.getPartnerId(), Currency.USD);
        ConversionReversal reversal = fxApi.reverseConversion(new ReverseConversionCommand(
                remittance.getQuoteId(), remittance.getPartnerId(), partnerAccount.id(),
                SystemAccounts.PAYSTACK_CLEARING_NGN, "REM:" + remittance.getId()));
        remittance.reverse(reason, reversal.usdLedgerTransactionId(), reversal.ngnLedgerTransactionId(), now);
    }

    // ---- asking the provider (manual refresh, and the reconciliation job) ----

    @Override
    public RemittanceView refresh(UUID remittanceId, UUID partnerId) {
        Remittance remittance = remittanceRepository.findById(remittanceId)
                .filter(r -> r.getPartnerId().equals(partnerId))
                .orElseThrow(() -> new BusinessException(
                        "REMITTANCE_NOT_FOUND", "No remittance with id " + remittanceId));
        return refreshInternal(remittance.getId());
    }

    /** Also called by the reconciliation job, which has no partner. */
    public RemittanceView refreshInternal(UUID remittanceId) {
        Remittance remittance = remittanceRepository.findById(remittanceId)
                .orElseThrow(() -> new BusinessException(
                        "REMITTANCE_NOT_FOUND", "No remittance with id " + remittanceId));

        if (remittance.getRecipientType() != RecipientType.BANK
                || remittance.getStatus() != RemittanceStatus.PROCESSING) {
            return toView(remittance);   // nothing to resolve
        }

        Optional<PayoutResult> remote = payoutApi.verify(remittance.getProviderReference());

        Outcome outcome;
        if (remote.isPresent()) {
            outcome = outcomeOf(remote.get().status(), remote.get().providerCode(), remote.get().message());
        } else {
            // "No record" only proves the request never arrived once enough time has passed.
            Duration age = Duration.between(remittance.getUpdatedAt(), clock.instant());
            if (age.compareTo(providerGrace) < 0) {
                return toView(remittance);
            }
            outcome = Outcome.reverse("Provider has no record of this payout", null);
        }
        return toView(settle(remittanceId, outcome));
    }

    /** Called when the provider reports a status by webhook. */
    public PayoutEventResult applyProviderUpdate(PayoutEvent event) {
        Optional<Remittance> found = remittanceRepository.findByProviderReference(event.providerReference());
        if (found.isEmpty()) {
            return PayoutEventResult.UNKNOWN_REFERENCE;
        }
        Remittance remittance = found.get();

        if (event.amountMinor() != null
                && event.amountMinor().longValue() != remittance.getReceiveAmount().minorUnits()) {
            log.error("Webhook amount {} does not match remittance {} ({})",
                    event.amountMinor(), remittance.getId(), remittance.getReceiveAmount().minorUnits());
            return PayoutEventResult.AMOUNT_MISMATCH;
        }

        settle(remittance.getId(), outcomeOf(event.status(), event.providerCode(), ""));
        return PayoutEventResult.APPLIED;
    }

    // ---- queries ----

    @Override
    @Transactional(readOnly = true)
    public RemittanceView get(UUID remittanceId, UUID partnerId) {
        return remittanceRepository.findById(remittanceId)
                .filter(r -> r.getPartnerId().equals(partnerId))
                .map(RemittanceService::toView)
                .orElseThrow(() -> new BusinessException(
                        "REMITTANCE_NOT_FOUND", "No remittance with id " + remittanceId));
    }

    @Override
    @Transactional(readOnly = true)
    public List<RemittanceView> recent(UUID partnerId) {
        return remittanceRepository.findTop20ByPartnerIdOrderByCreatedAtDesc(partnerId).stream()
                .map(RemittanceService::toView)
                .toList();
    }

    // ---- helpers ----

    /** Quote numbers never change, so reading them early is safe. The locked redemption happens when booking. */
    private QuoteView requireUsableQuote(CreateRemittanceCommand command) {
        QuoteView quote = fxApi.getQuote(command.quoteId(), command.partnerId());
        if ("USED".equals(quote.status())) {
            throw new BusinessException("QUOTE_ALREADY_USED", "This quote has already been used");
        }
        if ("EXPIRED".equals(quote.status())) {
            throw new BusinessException("QUOTE_EXPIRED", "This quote has expired. Request a new one.");
        }
        return quote;
    }

    /** An approval joins the surrounding transaction. A refusal is stored separately and survives its rollback. */
    private void screen(CreateRemittanceCommand command, String recipientRef, String recipientName, QuoteView quote) {
        ComplianceDecision decision = complianceApi.screen(new ScreeningRequest(
                command.partnerId(), command.idempotencyKey(), command.senderName(), command.senderCountry(),
                recipientRef, quote.sendAmount(), recipientName));
        if (decision.outcome() != ComplianceOutcome.APPROVED) {
            throw new BusinessException(
                    decision.outcome() == ComplianceOutcome.REJECTED ? "COMPLIANCE_REJECTED" : "COMPLIANCE_REFERRED",
                    decision.publicMessage());
        }
    }

    /** Only a customer's NGN account may receive. Never a system account, and never a partner's own. */
    private static void requireDeliverable(AccountView recipient) {
        if (!"CUSTOMER".equals(recipient.accountType()) || recipient.currency() != Currency.NGN) {
            throw new BusinessException("RECIPIENT_NOT_ELIGIBLE",
                    "The recipient must be a PayBridge customer NGN account");
        }
    }

    private static Outcome outcomeOf(PayoutStatus status, String providerCode, String detail) {
        return switch (status) {
            case SUCCESS -> Outcome.completed(providerCode);
            case FAILED -> Outcome.reverse("Provider reported the payout as failed", providerCode);
            case REVERSED -> Outcome.reverse("Provider reversed the payout", providerCode);
            case ACTION_REQUIRED -> Outcome.reverse(
                    "Provider requires OTP confirmation; disable OTP for API transfers", providerCode);
            case REJECTED -> Outcome.reverse("Provider rejected the payout: " + detail, providerCode);
            case PENDING, UNKNOWN -> Outcome.stillProcessing(providerCode);
        };
    }

    private RemittanceResult replay(Remittance existing, CreateRemittanceCommand command) {
        boolean sameRequest = existing.getQuoteId().equals(command.quoteId())
                && existing.getRecipientAccountNumber().equals(command.recipientAccountNumber())
                && Objects.equals(existing.getRecipientBankCode(), command.recipientBankCode())
                && existing.getSenderName().equals(command.senderName())
                && existing.getSenderCountry().equals(command.senderCountry());
        if (!sameRequest) {
            throw new BusinessException("IDEMPOTENCY_CONFLICT",
                    "This Idempotency-Key was already used for a different remittance");
        }
        return new RemittanceResult(toView(existing), true);
    }

    private static RemittanceView toView(Remittance r) {
        return new RemittanceView(
                r.getId(), r.getStatus().name(), r.getPartnerReference(), r.getQuoteId(),
                r.getSenderName(), r.getSenderCountry(), r.getRecipientType().name(),
                r.getRecipientAccountNumber(), r.getRecipientBankCode(), r.getRecipientName(),
                r.getSendAmount(), r.getFee(), r.getReceiveAmount(), r.getCustomerRate(),
                r.getFailureReason(), r.getCreatedAt(), r.getCompletedAt());
    }

    private void publishCompleted(Remittance remittance) {
        events.publishEvent(new RemittanceCompletedEvent(
                remittance.getId(), remittance.getPartnerId(), remittance.getPartnerReference(),
                remittance.getSendAmount(), remittance.getFee(), remittance.getReceiveAmount(),
                remittance.getCompletedAt()));
    }

    /** What we decided to do after hearing from the provider. */
    private record Outcome(Kind kind, String reason, String providerCode) {

        enum Kind { COMPLETED, REVERSE, STILL_PROCESSING }

        static Outcome completed(String providerCode) {
            return new Outcome(Kind.COMPLETED, null, providerCode);
        }

        static Outcome reverse(String reason, String providerCode) {
            return new Outcome(Kind.REVERSE, reason, providerCode);
        }

        static Outcome stillProcessing(String providerCode) {
            return new Outcome(Kind.STILL_PROCESSING, null, providerCode);
        }
    }
}