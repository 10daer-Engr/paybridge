package com.academy.paybridge.remittance.repository;

import com.academy.paybridge.remittance.domain.Remittance;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RemittanceRepository extends JpaRepository<Remittance, UUID> {

    Optional<Remittance> findByIdempotencyKey(String idempotencyKey);

    boolean existsByPartnerIdAndPartnerReference(UUID partnerId, String partnerReference);

    List<Remittance> findTop20ByPartnerIdOrderByCreatedAtDesc(UUID partnerId);
}