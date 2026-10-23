package com.academy.paybridge.transfer.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Immutable;

import java.time.Instant;

/** What the simulated provider did with one payout. Mirrors what a real provider would remember. */
@Entity
@Immutable
@Table(name = "simulated_payout")
public class SimulatedPayout {

    @Id
    @Column(length = 60)
    private String reference;

    @Column(nullable = false, updatable = false, length = 30)
    private String scenario;

    @Column(name = "amount_minor", nullable = false, updatable = false)
    private long amountMinor;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected SimulatedPayout() {
    }

    public static SimulatedPayout of(String reference, String scenario, long amountMinor, Instant now) {
        SimulatedPayout p = new SimulatedPayout();
        p.reference = reference;
        p.scenario = scenario;
        p.amountMinor = amountMinor;
        p.createdAt = now;
        return p;
    }

    public String getReference() { return reference; }
    public String getScenario() { return scenario; }
    public long getAmountMinor() { return amountMinor; }
    public Instant getCreatedAt() { return createdAt; }
}