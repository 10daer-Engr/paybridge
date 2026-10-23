package com.academy.paybridge.settlement.domain;

import com.academy.paybridge.shared.exception.BusinessException;
import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "settlement_batch")
public class SettlementBatch {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "partner_id", nullable = false, updatable = false)
    private UUID partnerId;

    @Column(nullable = false, updatable = false, length = 40)
    private String reference;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private SettlementStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "reconciliation_status", nullable = false, length = 10)
    private ReconciliationStatus reconciliationStatus;

    @Column(name = "item_count", nullable = false, updatable = false)
    private int itemCount;

    @Column(name = "gross_usd_minor", nullable = false, updatable = false)
    private long grossUsdMinor;

    @Column(name = "fee_usd_minor", nullable = false, updatable = false)
    private long feeUsdMinor;

    @Column(name = "payout_ngn_minor", nullable = false, updatable = false)
    private long payoutNgnMinor;

    @Column(name = "paid_usd_minor", nullable = false)
    private long paidUsdMinor;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "closed_at")
    private Instant closedAt;

    @Version
    private Long version;

    protected SettlementBatch() {
    }

    public static SettlementBatch generate(UUID partnerId, String reference, int itemCount,
                                           long grossUsdMinor, long feeUsdMinor, long payoutNgnMinor, Instant now) {
        SettlementBatch b = new SettlementBatch();
        b.partnerId = partnerId;
        b.reference = reference;
        b.status = SettlementStatus.OPEN;
        b.reconciliationStatus = ReconciliationStatus.PENDING;
        b.itemCount = itemCount;
        b.grossUsdMinor = grossUsdMinor;
        b.feeUsdMinor = feeUsdMinor;
        b.payoutNgnMinor = payoutNgnMinor;
        b.paidUsdMinor = 0;
        b.createdAt = now;
        return b;
    }

    public long outstandingMinor() {
        return grossUsdMinor - paidUsdMinor;
    }

    public void recordPayment(long amountMinor, Instant now) {
        requireOpen();
        if (amountMinor <= 0) {
            throw new BusinessException("INVALID_AMOUNT", "Amount must be greater than zero");
        }
        if (amountMinor > outstandingMinor()) {
            throw new BusinessException("OVERPAYMENT_NOT_ALLOWED",
                    "The payment is more than the outstanding amount of " + outstandingMinor() + " cents");
        }
        paidUsdMinor += amountMinor;
        closeIfSettled(now);
    }

    public void applyReconciliation(ReconciliationStatus result, Instant now) {
        requireOpen();
        this.reconciliationStatus = result;
        closeIfSettled(now);
    }

    private void requireOpen() {
        if (status != SettlementStatus.OPEN) {
            throw new BusinessException("SETTLEMENT_CLOSED", "This settlement is closed and can no longer change");
        }
    }

    /** Money alone is not enough: both sides must also agree on what the money is for. */
    private void closeIfSettled(Instant now) {
        if (status == SettlementStatus.OPEN
                && paidUsdMinor == grossUsdMinor
                && reconciliationStatus == ReconciliationStatus.MATCHED) {
            status = SettlementStatus.CLOSED;
            closedAt = now;
        }
    }

    public UUID getId() { return id; }
    public UUID getPartnerId() { return partnerId; }
    public String getReference() { return reference; }
    public SettlementStatus getStatus() { return status; }
    public ReconciliationStatus getReconciliationStatus() { return reconciliationStatus; }
    public int getItemCount() { return itemCount; }
    public long getGrossUsdMinor() { return grossUsdMinor; }
    public long getFeeUsdMinor() { return feeUsdMinor; }
    public long getPayoutNgnMinor() { return payoutNgnMinor; }
    public long getPaidUsdMinor() { return paidUsdMinor; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getClosedAt() { return closedAt; }
}