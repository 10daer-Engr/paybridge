package com.academy.paybridge.settlement.repository;

import com.academy.paybridge.settlement.domain.ReconciliationRun;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface ReconciliationRunRepository extends JpaRepository<ReconciliationRun, UUID> {
}