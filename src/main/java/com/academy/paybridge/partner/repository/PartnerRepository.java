package com.academy.paybridge.partner.repository;

import com.academy.paybridge.partner.domain.Partner;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface PartnerRepository extends JpaRepository<Partner, UUID> {

    boolean existsByNameIgnoreCase(String name);

    Optional<Partner> findByApiKeyHash(String apiKeyHash);
}