package com.academy.paybridge.fx.repository;

import com.academy.paybridge.fx.domain.FxQuote;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface FxQuoteRepository extends JpaRepository<FxQuote, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select q from FxQuote q where q.id = :id")
    Optional<FxQuote> findByIdForUpdate(@Param("id") UUID id);
}