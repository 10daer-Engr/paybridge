package com.academy.paybridge.remittance.repository;

import com.academy.paybridge.remittance.domain.Remittance;
import com.academy.paybridge.remittance.domain.RemittanceStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RemittanceRepository extends JpaRepository<Remittance, UUID> {

    Optional<Remittance> findByIdempotencyKey(String idempotencyKey);

    Optional<Remittance> findByProviderReference(String providerReference);

    boolean existsByPartnerIdAndPartnerReference(UUID partnerId, String partnerReference);

    List<Remittance> findTop20ByPartnerIdOrderByCreatedAtDesc(UUID partnerId);

    List<Remittance> findTop50ByStatusAndUpdatedAtBeforeOrderByUpdatedAtAsc(RemittanceStatus status, Instant cutoff);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from Remittance r where r.id = :id")
    Optional<Remittance> findByIdForUpdate(@Param("id") UUID id);
}