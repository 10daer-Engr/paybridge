package com.academy.paybridge.transfer.repository;

import com.academy.paybridge.transfer.domain.SimulatedPayout;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SimulatedPayoutRepository extends JpaRepository<SimulatedPayout, String> {
}