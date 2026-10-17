package com.academy.paybridge.remittance.service;

import com.academy.paybridge.account.api.AccountApi;
import com.academy.paybridge.account.api.AccountView;
import com.academy.paybridge.fx.api.BookConversionCommand;
import com.academy.paybridge.fx.api.ConversionBooking;
import com.academy.paybridge.fx.api.FxApi;
import com.academy.paybridge.fx.api.QuoteView;
import com.academy.paybridge.remittance.api.CreateRemittanceCommand;
import com.academy.paybridge.remittance.api.RemittanceApi;
import com.academy.paybridge.remittance.api.RemittanceResult;
import com.academy.paybridge.remittance.api.RemittanceView;
import com.academy.paybridge.remittance.domain.Remittance;
import com.academy.paybridge.remittance.repository.RemittanceRepository;
import com.academy.paybridge.shared.exception.BusinessException;
import com.academy.paybridge.shared.money.Currency;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class RemittanceService implements RemittanceApi {

    private final RemittanceRepository remittanceRepository;
    private final AccountApi accountApi;
    private final FxApi fxApi;
    private final Clock clock;

    public RemittanceService(RemittanceRepository remittanceRepository,
                             AccountApi accountApi,
                             FxApi fxApi,
                             Clock clock) {
        this.remittanceRepository = remittanceRepository;
        this.accountApi = accountApi;
        this.fxApi = fxApi;
        this.clock = clock;
    }

    /**
     * One transaction: the remittance row, the quote's redemption and both ledger postings
     * succeed together or not at all.
     */
    @Override
    @Transactional
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

        AccountView recipient = accountApi.getByAccountNumber(command.recipientAccountNumber());
        requireDeliverable(recipient);
        AccountView partnerAccount = accountApi.partnerAccount(command.partnerId(), Currency.USD);

        // Quote numbers never change once issued, so reading them now is safe. The authoritative,
        // locked redemption happens inside bookConversion below.
        QuoteView quote = fxApi.getQuote(command.quoteId(), command.partnerId());
        if ("USED".equals(quote.status())) {
            throw new BusinessException("QUOTE_ALREADY_USED", "This quote has already been used");
        }
        if ("EXPIRED".equals(quote.status())) {
            throw new BusinessException("QUOTE_EXPIRED", "This quote has expired. Request a new one.");
        }

        Instant now = clock.instant();

        // Flush first: a concurrent duplicate (same key, or same quote) fails here, before any money moves.
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
        return new RemittanceResult(toView(remittance), false);
    }

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

    /** Only a customer's NGN account may receive. Never a system account, and never a partner's own. */
    private static void requireDeliverable(AccountView recipient) {
        if (!"CUSTOMER".equals(recipient.accountType()) || recipient.currency() != Currency.NGN) {
            throw new BusinessException("RECIPIENT_NOT_ELIGIBLE",
                    "The recipient must be a PayBridge customer NGN account");
        }
    }

    private RemittanceResult replay(Remittance existing, CreateRemittanceCommand command) {
        boolean sameRequest = existing.getQuoteId().equals(command.quoteId())
                && existing.getRecipientAccountNumber().equals(command.recipientAccountNumber())
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
                r.getRecipientAccountNumber(),
                r.getSendAmount(), r.getFee(), r.getReceiveAmount(), r.getCustomerRate(),
                r.getFailureReason(), r.getCreatedAt(), r.getCompletedAt());
    }
}