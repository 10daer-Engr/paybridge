package com.academy.paybridge.settlement.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.Immutable;

import java.time.Instant;
import java.util.UUID;

/** One check of a partner statement against a batch. Kept forever, so the history of disagreements survives. */
@Entity
@Immutable
@Table(name = "settlement_reconciliation")
public class ReconciliationRun {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "batch_id", nullable = false, updatable = false)
    private UUID batchId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 10)
    private ReconciliationStatus status;

    @Column(name = "line_count", nullable = false, updatable = false)
    private int lineCount;

    @Column(name = "statement_total_minor", nullable = false, updatable = false)
    private long statementTotalMinor;

    @Column(name = "our_total_minor", nullable = false, updatable = false)
    private long ourTotalMinor;

    @Column(name = "break_count", nullable = false, updatable = false)
    private int breakCount;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected ReconciliationRun() {
    }

    public static ReconciliationRun of(UUID batchId, ReconciliationStatus status, int lineCount,
                                       long statementTotalMinor, long ourTotalMinor, int breakCount, Instant now) {
        ReconciliationRun r = new ReconciliationRun();
        r.batchId = batchId;
        r.status = status;
        r.lineCount = lineCount;
        r.statementTotalMinor = statementTotalMinor;
        r.ourTotalMinor = ourTotalMinor;
        r.breakCount = breakCount;
        r.createdAt = now;
        return r;
    }

    public UUID getId() { return id; }
}