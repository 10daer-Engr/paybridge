package com.academy.paybridge.settlement.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.Immutable;

import java.util.UUID;

@Entity
@Immutable
@Table(name = "settlement_break")
public class ReconciliationBreak {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "reconciliation_id", nullable = false, updatable = false)
    private UUID reconciliationId;

    @Column(nullable = false, updatable = false, length = 30)
    private String kind;

    @Column(nullable = false, updatable = false, length = 64)
    private String reference;

    @Column(name = "our_amount_minor", updatable = false)
    private Long ourAmountMinor;

    @Column(name = "their_amount_minor", updatable = false)
    private Long theirAmountMinor;

    protected ReconciliationBreak() {
    }

    public static ReconciliationBreak of(UUID reconciliationId, String kind, String reference,
                                         Long ourAmountMinor, Long theirAmountMinor) {
        ReconciliationBreak b = new ReconciliationBreak();
        b.reconciliationId = reconciliationId;
        b.kind = kind;
        b.reference = reference;
        b.ourAmountMinor = ourAmountMinor;
        b.theirAmountMinor = theirAmountMinor;
        return b;
    }
}