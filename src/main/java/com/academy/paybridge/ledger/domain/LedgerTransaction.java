package com.academy.paybridge.ledger.domain;

import com.academy.paybridge.ledger.api.LedgerTransactionType;
import com.academy.paybridge.ledger.api.PostingRequest;
import com.academy.paybridge.shared.money.Currency;
import jakarta.persistence.*;
import org.hibernate.annotations.Immutable;

import java.time.Instant;
import java.util.UUID;

@Entity
@Immutable
@Table(name = "ledger_transaction")
public class LedgerTransaction {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, updatable = false, length = 100)
    private String reference;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 30)
    private LedgerTransactionType type;

    @Column(updatable = false, length = 255)
    private String description;

    @Column(name = "correlation_id", updatable = false, length = 100)
    private String correlationId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 3)
    private Currency currency;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected LedgerTransaction() {
    }

    public static LedgerTransaction from(PostingRequest request, Instant now) {
        LedgerTransaction tx = new LedgerTransaction();
        tx.reference = request.reference();
        tx.type = request.type();
        tx.description = request.description();
        tx.correlationId = request.correlationId();
        tx.currency = request.currency();
        tx.createdAt = now;
        return tx;
    }

    public UUID getId() { return id; }
    public String getReference() { return reference; }
    public LedgerTransactionType getType() { return type; }
    public String getDescription() { return description; }
    public Currency getCurrency() { return currency; }
    public Instant getCreatedAt() { return createdAt; }
}