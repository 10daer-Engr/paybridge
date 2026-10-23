package com.academy.paybridge.compliance.repository;

import com.academy.paybridge.compliance.domain.ComplianceCheck;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.UUID;

public interface ComplianceCheckRepository extends JpaRepository<ComplianceCheck, UUID> {

    @Query("select coalesce(sum(c.amountMinor), 0L) from ComplianceCheck c "
            + "where c.outcome = com.academy.paybridge.compliance.api.ComplianceOutcome.APPROVED "
            + "and c.senderFingerprint = :fingerprint and c.createdAt >= :since")
    long approvedAmountBySender(@Param("fingerprint") String fingerprint, @Param("since") Instant since);

    @Query("select coalesce(sum(c.amountMinor), 0L) from ComplianceCheck c "
            + "where c.outcome = com.academy.paybridge.compliance.api.ComplianceOutcome.APPROVED "
            + "and c.recipientRef = :recipientRef and c.createdAt >= :since")
    long approvedAmountByRecipient(@Param("recipientRef") String recipientRef, @Param("since") Instant since);

    @Query("select coalesce(sum(c.amountMinor), 0L) from ComplianceCheck c "
            + "where c.outcome = com.academy.paybridge.compliance.api.ComplianceOutcome.APPROVED "
            + "and c.partnerId = :partnerId and c.createdAt >= :since")
    long approvedAmountByPartner(@Param("partnerId") UUID partnerId, @Param("since") Instant since);

    /**
     * A Postgres advisory lock held until the surrounding transaction ends. It locks an idea, such as
     * "sender X", not a row. The cast only exists because the function itself returns nothing.
     */
    @Query(value = "select cast(pg_advisory_xact_lock(hashtext(:key)) as text)", nativeQuery = true)
    String lockKey(@Param("key") String key);
}