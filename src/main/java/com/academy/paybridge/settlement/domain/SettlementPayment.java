package com.academy.paybridge.settlement.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.Immutable;

import java.time.Instant;
import java.util.UUID;

@Entity
@Immutable
@Table(name = "settlement_payment")
public class SettlementPayment {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "batch_id", nullable = false, updatable = false)
    private UUID batchId;

    @Column(name = "partner_id", nullable = false, updatable = false)
    private UUID partnerId;

    @Column(name = "idempotency_key", nullable = false, updatable = false, length = 80)
    private String idempotencyKey;

    @Column(name = "wire_reference", nullable = false, updatable = false, length = 64)
    private String wireReference;

    @Column(name = "amount_usd_minor", nullable = false, updatable = false)
    private long amountUsdMinor;

    @Column(name = "ledger_transaction_id", nullable = false, updatable = false)
    private UUID ledgerTransactionId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected SettlementPayment() {
    }

    public static SettlementPayment of(UUID batchId, UUID partnerId, String idempotencyKey, String wireReference,
                                       long amountUsdMinor, UUID ledgerTransactionId, Instant now) {
        SettlementPayment p = new SettlementPayment();
        p.batchId = batchId;
        p.partnerId = partnerId;
        p.idempotencyKey = idempotencyKey;
        p.wireReference = wireReference;
        p.amountUsdMinor = amountUsdMinor;
        p.ledgerTransactionId = ledgerTransactionId;
        p.createdAt = now;
        return p;
    }

    public UUID getId() { return id; }
    public UUID getBatchId() { return batchId; }
    public String getWireReference() { return wireReference; }
    public long getAmountUsdMinor() { return amountUsdMinor; }
    public Instant getCreatedAt() { return createdAt; }
}