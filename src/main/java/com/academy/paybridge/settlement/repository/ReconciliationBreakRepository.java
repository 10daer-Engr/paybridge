package com.academy.paybridge.settlement.repository;

import com.academy.paybridge.settlement.domain.ReconciliationBreak;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface ReconciliationBreakRepository extends JpaRepository<ReconciliationBreak, UUID> {
}