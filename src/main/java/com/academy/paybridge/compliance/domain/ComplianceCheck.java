package com.academy.paybridge.compliance.domain;

import com.academy.paybridge.compliance.api.ComplianceOutcome;
import jakarta.persistence.*;
import org.hibernate.annotations.Immutable;

import java.time.Instant;
import java.util.UUID;

@Entity
@Immutable
@Table(name = "compliance_check")
public class ComplianceCheck {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "partner_id", nullable = false, updatable = false)
    private UUID partnerId;

    @Column(name = "subject_ref", nullable = false, updatable = false, length = 80)
    private String subjectRef;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 10)
    private ComplianceOutcome outcome;

    @Column(nullable = false, updatable = false, length = 500)
    private String reasons;

    @Column(name = "sender_name", nullable = false, updatable = false, length = 150)
    private String senderName;

    @Column(name = "sender_country", nullable = false, updatable = false, length = 2)
    private String senderCountry;

    @Column(name = "sender_fingerprint", nullable = false, updatable = false, length = 64)
    private String senderFingerprint;

    @Column(name = "recipient_ref", nullable = false, updatable = false, length = 40)
    private String recipientRef;

    @Column(name = "amount_minor", nullable = false, updatable = false)
    private long amountMinor;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected ComplianceCheck() {
    }

    public static ComplianceCheck of(UUID partnerId, String subjectRef, ComplianceOutcome outcome, String reasons,
                                     String senderName, String senderCountry, String senderFingerprint,
                                     String recipientRef, long amountMinor, Instant now) {
        ComplianceCheck c = new ComplianceCheck();
        c.partnerId = partnerId;
        c.subjectRef = subjectRef;
        c.outcome = outcome;
        c.reasons = reasons.length() > 500 ? reasons.substring(0, 500) : reasons;
        c.senderName = senderName.length() > 150 ? senderName.substring(0, 150) : senderName;
        c.senderCountry = senderCountry;
        c.senderFingerprint = senderFingerprint;
        c.recipientRef = recipientRef.length() > 40 ? recipientRef.substring(0, 40) : recipientRef;
        c.amountMinor = amountMinor;
        c.createdAt = now;
        return c;
    }

    public UUID getId() { return id; }
    public ComplianceOutcome getOutcome() { return outcome; }
    public String getReasons() { return reasons; }
    public Instant getCreatedAt() { return createdAt; }
}