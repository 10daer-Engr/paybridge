package com.academy.paybridge.remittance.domain;

import com.academy.paybridge.shared.exception.BusinessException;
import com.academy.paybridge.shared.money.Currency;
import com.academy.paybridge.shared.money.Money;
import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** One payment from a partner's sender to a recipient in Nigeria. Amounts are snapshots of the quote. */
@Entity
@Table(name = "remittance")
public class Remittance {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "partner_id", nullable = false, updatable = false)
    private UUID partnerId;

    @Column(name = "idempotency_key", nullable = false, updatable = false, length = 80)
    private String idempotencyKey;

    @Column(name = "partner_reference", updatable = false, length = 64)
    private String partnerReference;

    @Column(name = "quote_id", nullable = false, updatable = false)
    private UUID quoteId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private RemittanceStatus status;

    @Column(name = "sender_name", nullable = false, updatable = false, length = 150)
    private String senderName;

    @Column(name = "sender_country", nullable = false, updatable = false, length = 2)
    private String senderCountry;

    @Enumerated(EnumType.STRING)
    @Column(name = "recipient_type", nullable = false, updatable = false, length = 10)
    private RecipientType recipientType;

    @Column(name = "recipient_account_id", updatable = false)
    private UUID recipientAccountId;

    @Column(name = "recipient_account_number", nullable = false, updatable = false, length = 10)
    private String recipientAccountNumber;

    @Column(name = "recipient_bank_code", updatable = false, length = 10)
    private String recipientBankCode;

    @Column(name = "recipient_name", updatable = false, length = 150)
    private String recipientName;

    @Column(name = "recipient_provider_code", updatable = false, length = 50)
    private String recipientProviderCode;

    @Column(name = "send_amount_minor", nullable = false, updatable = false)
    private long sendAmountMinor;

    @Column(name = "fee_minor", nullable = false, updatable = false)
    private long feeMinor;

    @Column(name = "receive_amount_minor", nullable = false, updatable = false)
    private long receiveAmountMinor;

    @Column(name = "customer_rate", nullable = false, updatable = false, precision = 20, scale = 6)
    private BigDecimal customerRate;

    @Column(name = "usd_ledger_transaction_id")
    private UUID usdLedgerTransactionId;

    @Column(name = "ngn_ledger_transaction_id")
    private UUID ngnLedgerTransactionId;

    @Column(name = "usd_reversal_ledger_transaction_id")
    private UUID usdReversalLedgerTransactionId;

    @Column(name = "ngn_reversal_ledger_transaction_id")
    private UUID ngnReversalLedgerTransactionId;

    @Column(name = "provider_reference", length = 60)
    private String providerReference;

    @Column(name = "provider_transfer_code", length = 50)
    private String providerTransferCode;

    @Column(name = "failure_reason", length = 255)
    private String failureReason;

    @Version
    private Long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    protected Remittance() {
    }

    public static Remittance receiveToAccount(UUID partnerId, String idempotencyKey, String partnerReference,
                                              UUID quoteId, String senderName, String senderCountry,
                                              Money sendAmount, Money fee, Money receiveAmount,
                                              BigDecimal customerRate,
                                              UUID recipientAccountId, String recipientAccountNumber,
                                              Instant now) {
        Remittance r = base(partnerId, idempotencyKey, partnerReference, quoteId, senderName, senderCountry,
                sendAmount, fee, receiveAmount, customerRate, now);
        r.recipientType = RecipientType.ACCOUNT;
        r.recipientAccountId = recipientAccountId;
        r.recipientAccountNumber = recipientAccountNumber;
        return r;
    }

    public static Remittance receiveToBank(UUID partnerId, String idempotencyKey, String partnerReference,
                                           UUID quoteId, String senderName, String senderCountry,
                                           Money sendAmount, Money fee, Money receiveAmount,
                                           BigDecimal customerRate,
                                           String bankAccountNumber, String bankCode,
                                           String recipientName, String recipientProviderCode,
                                           Instant now) {
        Remittance r = base(partnerId, idempotencyKey, partnerReference, quoteId, senderName, senderCountry,
                sendAmount, fee, receiveAmount, customerRate, now);
        r.recipientType = RecipientType.BANK;
        r.recipientAccountNumber = bankAccountNumber;
        r.recipientBankCode = bankCode;
        r.recipientName = recipientName;
        r.recipientProviderCode = recipientProviderCode;
        return r;
    }

    private static Remittance base(UUID partnerId, String idempotencyKey, String partnerReference, UUID quoteId,
                                   String senderName, String senderCountry, Money sendAmount, Money fee,
                                   Money receiveAmount, BigDecimal customerRate, Instant now) {
        Remittance r = new Remittance();
        r.partnerId = partnerId;
        r.idempotencyKey = idempotencyKey;
        r.partnerReference = partnerReference;
        r.quoteId = quoteId;
        r.status = RemittanceStatus.PENDING;
        r.senderName = senderName;
        r.senderCountry = senderCountry;
        r.sendAmountMinor = sendAmount.minorUnits();
        r.feeMinor = fee.minorUnits();
        r.receiveAmountMinor = receiveAmount.minorUnits();
        r.customerRate = customerRate;
        r.createdAt = now;
        r.updatedAt = now;
        return r;
    }

    /** Delivery into a PayBridge account: instant, so PENDING goes straight to COMPLETED. */
    public void markCompleted(UUID usdLedgerTransactionId, UUID ngnLedgerTransactionId, Instant now) {
        moveTo(RemittanceStatus.COMPLETED, now);
        this.usdLedgerTransactionId = usdLedgerTransactionId;
        this.ngnLedgerTransactionId = ngnLedgerTransactionId;
        this.completedAt = now;
    }

    /** Bank payout: the exchange is booked and the naira is committed to the provider, awaiting its answer. */
    public void markProcessing(UUID usdLedgerTransactionId, UUID ngnLedgerTransactionId,
                               String providerReference, Instant now) {
        moveTo(RemittanceStatus.PROCESSING, now);
        this.usdLedgerTransactionId = usdLedgerTransactionId;
        this.ngnLedgerTransactionId = ngnLedgerTransactionId;
        this.providerReference = providerReference;
    }

    public void confirmDelivered(Instant now) {
        moveTo(RemittanceStatus.COMPLETED, now);
        this.completedAt = now;
    }

    /** The exchange was undone by the reversal postings. */
    public void reverse(String reason, UUID usdReversalTransactionId, UUID ngnReversalTransactionId, Instant now) {
        moveTo(RemittanceStatus.REVERSED, now);
        this.failureReason = reason != null && reason.length() > 255 ? reason.substring(0, 255) : reason;
        this.usdReversalLedgerTransactionId = usdReversalTransactionId;
        this.ngnReversalLedgerTransactionId = ngnReversalTransactionId;
    }

    public void fail(String reason, Instant now) {
        moveTo(RemittanceStatus.FAILED, now);
        this.failureReason = reason != null && reason.length() > 255 ? reason.substring(0, 255) : reason;
    }

    public void recordProviderTransferCode(String providerTransferCode) {
        this.providerTransferCode = providerTransferCode;
    }

    private void moveTo(RemittanceStatus next, Instant now) {
        if (!status.canMoveTo(next)) {
            throw new BusinessException("INVALID_STATE_TRANSITION",
                    "Remittance cannot move from " + status + " to " + next);
        }
        this.status = next;
        this.updatedAt = now;
    }

    public UUID getId() { return id; }
    public UUID getPartnerId() { return partnerId; }
    public String getPartnerReference() { return partnerReference; }
    public UUID getQuoteId() { return quoteId; }
    public RemittanceStatus getStatus() { return status; }
    public String getSenderName() { return senderName; }
    public String getSenderCountry() { return senderCountry; }
    public RecipientType getRecipientType() { return recipientType; }
    public String getRecipientAccountNumber() { return recipientAccountNumber; }
    public String getRecipientBankCode() { return recipientBankCode; }
    public String getRecipientName() { return recipientName; }
    public String getProviderReference() { return providerReference; }
    public Money getSendAmount() { return new Money(sendAmountMinor, Currency.USD); }
    public Money getFee() { return new Money(feeMinor, Currency.USD); }
    public Money getReceiveAmount() { return new Money(receiveAmountMinor, Currency.NGN); }
    public BigDecimal getCustomerRate() { return customerRate; }
    public String getFailureReason() { return failureReason; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public Instant getCompletedAt() { return completedAt; }
}