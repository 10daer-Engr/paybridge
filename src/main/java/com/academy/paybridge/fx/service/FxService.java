package com.academy.paybridge.fx.service;

import com.academy.paybridge.fx.api.CurrentRate;
import com.academy.paybridge.fx.api.FxApi;
import com.academy.paybridge.fx.api.QuoteView;
import com.academy.paybridge.fx.client.RateProvider;
import com.academy.paybridge.fx.client.RateProvider.MidRate;
import com.academy.paybridge.fx.config.FxProperties;
import com.academy.paybridge.fx.domain.FxMath;
import com.academy.paybridge.fx.domain.FxQuote;
import com.academy.paybridge.fx.domain.FxQuoteStatus;
import com.academy.paybridge.fx.repository.FxQuoteRepository;
import com.academy.paybridge.shared.exception.BusinessException;
import com.academy.paybridge.shared.money.Currency;
import com.academy.paybridge.shared.money.Money;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import com.academy.paybridge.account.api.SystemAccounts;
import com.academy.paybridge.fx.api.BookConversionCommand;
import com.academy.paybridge.fx.api.ConversionBooking;
import com.academy.paybridge.ledger.api.LedgerApi;
import com.academy.paybridge.ledger.api.LedgerTransactionType;
import com.academy.paybridge.ledger.api.PostingLine;
import com.academy.paybridge.ledger.api.PostingRequest;
import com.academy.paybridge.ledger.api.PostingResult;
import com.academy.paybridge.fx.api.ConversionReversal;
import com.academy.paybridge.fx.api.ReverseConversionCommand;
import java.util.ArrayList;
import java.util.List;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

@Service
public class FxService implements FxApi {

    /** A rate older than this is not trusted. Quoting a stale rate is how FX desks lose money. */
    private static final Duration MAX_RATE_AGE = Duration.ofSeconds(60);

    private final FxQuoteRepository quoteRepository;
    private final RateProvider rateProvider;
    private final FxProperties properties;
    private final Clock clock;
    private final LedgerApi ledgerApi;

    public FxService(FxQuoteRepository quoteRepository, RateProvider rateProvider,
                     FxProperties properties, Clock clock, LedgerApi ledgerApi) {
        this.quoteRepository = quoteRepository;
        this.rateProvider = rateProvider;
        this.properties = properties;
        this.clock = clock;
        this.ledgerApi = ledgerApi;
    }

    @Override
    public CurrentRate currentRate(Currency from, Currency to) {
        requireSupported(from, to);
        MidRate mid = freshMidRate(from, to);
        return new CurrentRate(from, to, mid.rate(),
                FxMath.customerRate(mid.rate(), properties.spreadBps()),
                properties.spreadBps(), mid.asOf(), mid.source());
    }

    @Override
    @Transactional
    public QuoteView createQuote(UUID requesterId, Money sendAmount, Currency receiveCurrency) {
        Currency from = sendAmount.currency();
        requireSupported(from, receiveCurrency);
        requireWithinLimits(sendAmount);

        MidRate mid = freshMidRate(from, receiveCurrency);
        Money fee = Money.ofMajor(properties.feeUsd(), from);
        FxMath.Computation computation = FxMath.compute(
                sendAmount.minorUnits(), fee.minorUnits(), mid.rate(), properties.spreadBps());

        Instant now = clock.instant();
        FxQuote quote = quoteRepository.save(FxQuote.create(
                requesterId, sendAmount, fee, receiveCurrency,
                mid.rate(), properties.spreadBps(), computation,
                mid.source(), mid.asOf(), now, now.plus(properties.quoteTtl())));

        return toView(quote, now);
    }

    @Override
    @Transactional(readOnly = true)
    public QuoteView getQuote(UUID quoteId, UUID requesterId) {
        FxQuote quote = quoteRepository.findById(quoteId)
                .filter(q -> q.getRequesterId().equals(requesterId))
                .orElseThrow(() -> notFound(quoteId));
        return toView(quote, clock.instant());
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public QuoteView consumeQuote(UUID quoteId, UUID requesterId) {
        FxQuote quote = quoteRepository.findByIdForUpdate(quoteId)
                .filter(q -> q.getRequesterId().equals(requesterId))
                .orElseThrow(() -> notFound(quoteId));

        Instant now = clock.instant();
        quote.consume(now);
        return toView(quote, now);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public ConversionBooking bookConversion(BookConversionCommand command) {
        // Locks the quote row and marks it USED. Because this shares one transaction with the postings
        // below, a failure anywhere (such as a credit limit) rolls the quote back to ACTIVE.
        QuoteView quote = consumeQuote(command.quoteId(), command.requesterId());

        Money send = quote.sendAmount();
        Money fee = quote.fee();
        Money converted = quote.convertedAmount();
        Money receive = quote.receiveAmount();
        Money midValue = receive.plus(quote.margin());   // receive + margin = value at the mid rate

        // USD side: the partner owes the full send amount; we hold the converted part and earn the fee.
        List<PostingLine> usdLines = new ArrayList<>();
        usdLines.add(PostingLine.debit(command.partnerAccountId(), send));
        usdLines.add(PostingLine.credit(SystemAccounts.FX_POSITION_USD, converted));
        if (fee.isPositive()) {
            usdLines.add(PostingLine.credit(SystemAccounts.FEE_REVENUE_USD, fee));
        }
        PostingResult usd = ledgerApi.post(new PostingRequest(
                command.reference() + ":USD", LedgerTransactionType.CONVERSION,
                "Exchange, USD side", usdLines, command.reference()));

        // NGN side: we sell naira at the mid rate, the recipient gets the customer rate, we keep the gap.
        // Always USD first, then NGN, so concurrent exchanges lock accounts in the same order.
        List<PostingLine> ngnLines = new ArrayList<>();
        ngnLines.add(PostingLine.debit(SystemAccounts.FX_POSITION_NGN, midValue));
        ngnLines.add(PostingLine.credit(command.destinationAccountId(), receive));
        if (quote.margin().isPositive()) {
            ngnLines.add(PostingLine.credit(SystemAccounts.FX_REVENUE_NGN, quote.margin()));
        }
        PostingResult ngn = ledgerApi.post(new PostingRequest(
                command.reference() + ":NGN", LedgerTransactionType.CONVERSION,
                "Exchange, NGN side", ngnLines, command.reference()));

        return new ConversionBooking(quote, usd.transactionId(), ngn.transactionId());
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public ConversionReversal reverseConversion(ReverseConversionCommand command) {
        FxQuote quote = quoteRepository.findById(command.quoteId())
                .filter(q -> q.getRequesterId().equals(command.requesterId()))
                .orElseThrow(() -> notFound(command.quoteId()));
        if (quote.getStatus() != FxQuoteStatus.USED) {
            throw new BusinessException("QUOTE_NOT_BOOKED", "Only a booked exchange can be reversed");
        }

        Money send = quote.getSendAmount();
        Money fee = quote.getFee();
        Money converted = quote.getConvertedAmount();
        Money receive = quote.getReceiveAmount();
        Money margin = quote.getMargin();
        Money midValue = receive.plus(margin);

        // USD: give the partner back everything, including the fee (the service was not delivered).
        List<PostingLine> usdLines = new ArrayList<>();
        usdLines.add(PostingLine.debit(SystemAccounts.FX_POSITION_USD, converted));
        if (fee.isPositive()) {
            usdLines.add(PostingLine.debit(SystemAccounts.FEE_REVENUE_USD, fee));
        }
        usdLines.add(PostingLine.credit(command.partnerAccountId(), send));
        PostingResult usd = ledgerApi.post(new PostingRequest(
                command.reference() + ":REV:USD", LedgerTransactionType.REVERSAL,
                "Exchange reversal, USD side", usdLines, command.reference()));

        // NGN: take the naira back out of where it was sent, and take back our margin.
        List<PostingLine> ngnLines = new ArrayList<>();
        ngnLines.add(PostingLine.debit(command.destinationAccountId(), receive));
        if (margin.isPositive()) {
            ngnLines.add(PostingLine.debit(SystemAccounts.FX_REVENUE_NGN, margin));
        }
        ngnLines.add(PostingLine.credit(SystemAccounts.FX_POSITION_NGN, midValue));
        PostingResult ngn = ledgerApi.post(new PostingRequest(
                command.reference() + ":REV:NGN", LedgerTransactionType.REVERSAL,
                "Exchange reversal, NGN side", ngnLines, command.reference()));

        return new ConversionReversal(usd.transactionId(), ngn.transactionId());
    }

    // ---- helpers ----

    private static void requireSupported(Currency from, Currency to) {
        if (from != Currency.USD || to != Currency.NGN) {
            throw new BusinessException("UNSUPPORTED_CORRIDOR", "Only USD to NGN is supported");
        }
    }

    private void requireWithinLimits(Money sendAmount) {
        Money min = Money.ofMajor(properties.minSendUsd(), sendAmount.currency());
        Money max = Money.ofMajor(properties.maxSendUsd(), sendAmount.currency());
        if (sendAmount.isLessThan(min) || max.isLessThan(sendAmount)) {
            throw new BusinessException("AMOUNT_OUT_OF_RANGE",
                    "The send amount must be between " + min.toMajor().toPlainString()
                            + " and " + max.toMajor().toPlainString() + " " + sendAmount.currency());
        }
    }

    private MidRate freshMidRate(Currency from, Currency to) {
        MidRate mid = rateProvider.currentMidRate(from, to);
        if (Duration.between(mid.asOf(), clock.instant()).compareTo(MAX_RATE_AGE) > 0) {
            throw new BusinessException("RATE_UNAVAILABLE", "No fresh exchange rate is available right now");
        }
        return mid;
    }

    private static BusinessException notFound(UUID quoteId) {
        return new BusinessException("QUOTE_NOT_FOUND", "No quote with id " + quoteId);
    }

    private static QuoteView toView(FxQuote q, Instant now) {
        String status = q.getStatus() == FxQuoteStatus.USED
                ? "USED"
                : (q.isExpiredAt(now) ? "EXPIRED" : "ACTIVE");
        return new QuoteView(
                q.getId(), q.getFromCurrency(), q.getToCurrency(),
                q.getSendAmount(), q.getFee(), q.getConvertedAmount(),
                q.getMidRate(), q.getCustomerRate(), q.getSpreadBps(),
                q.getReceiveAmount(), q.getMargin(),
                q.getCreatedAt(), q.getExpiresAt(), status);
    }
}