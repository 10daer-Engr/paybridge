package com.academy.paybridge.settlement.repository;

import com.academy.paybridge.settlement.domain.SettlementItem;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface SettlementItemRepository extends JpaRepository<SettlementItem, UUID> {

    /** Locks the rows it returns, so two simultaneous batch requests cannot claim the same items. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    List<SettlementItem> findTop1000ByPartnerIdAndBatchIdIsNullAndCompletedAtBeforeOrderByCompletedAtAsc(
            UUID partnerId, Instant cutoff);

    List<SettlementItem> findByBatchIdOrderByCompletedAtAsc(UUID batchId);
}