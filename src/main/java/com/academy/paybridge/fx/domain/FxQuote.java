package com.academy.paybridge.fx.domain;

import com.academy.paybridge.shared.exception.BusinessException;
import com.academy.paybridge.shared.money.Currency;
import com.academy.paybridge.shared.money.Money;
import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "fx_quote")
public class FxQuote {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "requester_id", nullable = false, updatable = false)
    private UUID requesterId;

    @Enumerated(EnumType.STRING)
    @Column(name = "from_currency", nullable = false, updatable = false, length = 3)
    private Currency fromCurrency;

    @Enumerated(EnumType.STRING)
    @Column(name = "to_currency", nullable = false, updatable = false, length = 3)
    private Currency toCurrency;

    @Column(name = "send_amount_minor", nullable = false, updatable = false)
    private long sendAmountMinor;

    @Column(name = "fee_minor", nullable = false, updatable = false)
    private long feeMinor;

    @Column(name = "mid_rate", nullable = false, updatable = false, precision = 20, scale = 6)
    private BigDecimal midRate;

    @Column(name = "spread_bps", nullable = false, updatable = false)
    private int spreadBps;

    @Column(name = "customer_rate", nullable = false, updatable = false, precision = 20, scale = 6)
    private BigDecimal customerRate;

    @Column(name = "receive_amount_minor", nullable = false, updatable = false)
    private long receiveAmountMinor;

    @Column(name = "margin_minor", nullable = false, updatable = false)
    private long marginMinor;

    @Column(name = "rate_source", nullable = false, updatable = false, length = 30)
    private String rateSource;

    @Column(name = "rate_as_of", nullable = false, updatable = false)
    private Instant rateAsOf;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private FxQuoteStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private Instant expiresAt;

    @Column(name = "used_at")
    private Instant usedAt;

    @Version
    private Long version;

    protected FxQuote() {
    }

    public static FxQuote create(UUID requesterId, Money sendAmount, Money fee, Currency toCurrency,
                                 BigDecimal midRate, int spreadBps, FxMath.Computation computation,
                                 String rateSource, Instant rateAsOf, Instant now, Instant expiresAt) {
        FxQuote q = new FxQuote();
        q.requesterId = requesterId;
        q.fromCurrency = sendAmount.currency();
        q.toCurrency = toCurrency;
        q.sendAmountMinor = sendAmount.minorUnits();
        q.feeMinor = fee.minorUnits();
        q.midRate = midRate;
        q.spreadBps = spreadBps;
        q.customerRate = computation.customerRate();
        q.receiveAmountMinor = computation.receiveMinor();
        q.marginMinor = computation.marginMinor();
        q.rateSource = rateSource;
        q.rateAsOf = rateAsOf;
        q.status = FxQuoteStatus.ACTIVE;
        q.createdAt = now;
        q.expiresAt = expiresAt;
        return q;
    }

    /** Redeems the quote. Must be called under a row lock so two callers cannot both succeed. */
    public void consume(Instant now) {
        if (status != FxQuoteStatus.ACTIVE) {
            throw new BusinessException("QUOTE_ALREADY_USED", "This quote has already been used");
        }
        if (!now.isBefore(expiresAt)) {
            throw new BusinessException("QUOTE_EXPIRED", "This quote has expired. Request a new one.");
        }
        this.status = FxQuoteStatus.USED;
        this.usedAt = now;
    }

    public boolean isExpiredAt(Instant now) {
        return status == FxQuoteStatus.ACTIVE && !now.isBefore(expiresAt);
    }

    public UUID getId() { return id; }
    public UUID getRequesterId() { return requesterId; }
    public Currency getFromCurrency() { return fromCurrency; }
    public Currency getToCurrency() { return toCurrency; }
    public Money getSendAmount() { return new Money(sendAmountMinor, fromCurrency); }
    public Money getFee() { return new Money(feeMinor, fromCurrency); }
    public Money getConvertedAmount() { return new Money(sendAmountMinor - feeMinor, fromCurrency); }
    public BigDecimal getMidRate() { return midRate; }
    public int getSpreadBps() { return spreadBps; }
    public BigDecimal getCustomerRate() { return customerRate; }
    public Money getReceiveAmount() { return new Money(receiveAmountMinor, toCurrency); }
    public Money getMargin() { return new Money(marginMinor, toCurrency); }
    public FxQuoteStatus getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getExpiresAt() { return expiresAt; }
}