package com.academy.paybridge.settlement.domain;

import com.academy.paybridge.shared.exception.BusinessException;
import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/** One completed remittance waiting to be settled, or already settled in a batch. */
@Entity
@Table(name = "settlement_item")
public class SettlementItem {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "partner_id", nullable = false, updatable = false)
    private UUID partnerId;

    @Column(name = "remittance_id", nullable = false, updatable = false)
    private UUID remittanceId;

    @Column(name = "batch_id")
    private UUID batchId;

    @Column(nullable = false, updatable = false, length = 64)
    private String reference;

    @Column(name = "send_amount_minor", nullable = false, updatable = false)
    private long sendAmountMinor;

    @Column(name = "fee_minor", nullable = false, updatable = false)
    private long feeMinor;

    @Column(name = "receive_amount_minor", nullable = false, updatable = false)
    private long receiveAmountMinor;

    @Column(name = "completed_at", nullable = false, updatable = false)
    private Instant completedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected SettlementItem() {
    }

    public static SettlementItem pending(UUID partnerId, UUID remittanceId, String reference,
                                         long sendAmountMinor, long feeMinor, long receiveAmountMinor,
                                         Instant completedAt) {
        SettlementItem i = new SettlementItem();
        i.partnerId = partnerId;
        i.remittanceId = remittanceId;
        i.reference = reference;
        i.sendAmountMinor = sendAmountMinor;
        i.feeMinor = feeMinor;
        i.receiveAmountMinor = receiveAmountMinor;
        i.completedAt = completedAt;
        i.createdAt = completedAt;
        return i;
    }

    public void assignTo(UUID batchId) {
        if (this.batchId != null) {
            throw new BusinessException("ALREADY_SETTLED", "This remittance is already part of a settlement");
        }
        this.batchId = batchId;
    }

    public UUID getRemittanceId() { return remittanceId; }
    public UUID getBatchId() { return batchId; }
    public String getReference() { return reference; }
    public long getSendAmountMinor() { return sendAmountMinor; }
    public long getFeeMinor() { return feeMinor; }
    public long getReceiveAmountMinor() { return receiveAmountMinor; }
    public Instant getCompletedAt() { return completedAt; }
}