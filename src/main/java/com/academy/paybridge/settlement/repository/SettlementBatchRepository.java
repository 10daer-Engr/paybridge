package com.academy.paybridge.settlement.repository;

import com.academy.paybridge.settlement.domain.SettlementBatch;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SettlementBatchRepository extends JpaRepository<SettlementBatch, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select b from SettlementBatch b where b.id = :id")
    Optional<SettlementBatch> findByIdForUpdate(@Param("id") UUID id);

    List<SettlementBatch> findTop50ByPartnerIdOrderByCreatedAtDesc(UUID partnerId);
}