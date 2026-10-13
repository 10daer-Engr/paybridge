package com.academy.paybridge.partner.domain;

import com.academy.paybridge.shared.money.Currency;
import com.academy.paybridge.shared.money.Money;
import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "partner")
public class Partner {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, length = 150)
    private String name;

    @Column(name = "contact_email", nullable = false, length = 254)
    private String contactEmail;

    @Column(name = "api_key_hash", nullable = false, length = 64)
    private String apiKeyHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PartnerStatus status;

    @Column(name = "credit_limit_usd_minor", nullable = false)
    private long creditLimitUsdMinor;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected Partner() {
    }

    public static Partner register(String name, String contactEmail, String apiKeyHash,
                                   Money creditLimit, Instant now) {
        Partner p = new Partner();
        p.name = name;
        p.contactEmail = contactEmail;
        p.apiKeyHash = apiKeyHash;
        p.status = PartnerStatus.ACTIVE;
        p.creditLimitUsdMinor = creditLimit.minorUnits();
        p.createdAt = now;
        return p;
    }

    public UUID getId() { return id; }
    public String getName() { return name; }
    public PartnerStatus getStatus() { return status; }
    public Money getCreditLimit() { return new Money(creditLimitUsdMinor, Currency.USD); }
}