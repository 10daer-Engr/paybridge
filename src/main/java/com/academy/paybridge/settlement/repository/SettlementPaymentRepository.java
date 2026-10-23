package com.academy.paybridge.settlement.repository;

import com.academy.paybridge.settlement.domain.SettlementPayment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface SettlementPaymentRepository extends JpaRepository<SettlementPayment, UUID> {

    Optional<SettlementPayment> findByIdempotencyKey(String idempotencyKey);
}