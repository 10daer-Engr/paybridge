package com.academy.paybridge.partner.service;

import com.academy.paybridge.account.api.AccountApi;
import com.academy.paybridge.account.api.AccountView;
import com.academy.paybridge.partner.api.PartnerApi;
import com.academy.paybridge.partner.api.PartnerView;
import com.academy.paybridge.partner.api.RegisteredPartner;
import com.academy.paybridge.partner.domain.Partner;
import com.academy.paybridge.partner.domain.PartnerStatus;
import com.academy.paybridge.partner.repository.PartnerRepository;
import com.academy.paybridge.shared.exception.BusinessException;
import com.academy.paybridge.shared.money.Currency;
import com.academy.paybridge.shared.money.Money;
import com.academy.paybridge.shared.security.ApiKeys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.Optional;
import java.util.UUID;

@Service
public class PartnerService implements PartnerApi {

    private final PartnerRepository partnerRepository;
    private final AccountApi accountApi;
    private final Clock clock;
    private final Money defaultCreditLimit;

    public PartnerService(PartnerRepository partnerRepository,
                          AccountApi accountApi,
                          Clock clock,
                          @Value("${paybridge.partners.default-credit-limit-usd:10000.00}")
                          BigDecimal defaultCreditLimitUsd) {
        this.partnerRepository = partnerRepository;
        this.accountApi = accountApi;
        this.clock = clock;
        this.defaultCreditLimit = Money.ofMajor(defaultCreditLimitUsd, Currency.USD);
    }

    @Override
    @Transactional
    public RegisteredPartner register(String name, String contactEmail) {
        String cleanName = name.trim();
        if (partnerRepository.existsByNameIgnoreCase(cleanName)) {
            throw new BusinessException("PARTNER_ALREADY_REGISTERED", "A partner with that name already exists");
        }

        String apiKey = ApiKeys.generate(API_KEY_PREFIX);
        Partner saved = partnerRepository.save(Partner.register(
                cleanName, contactEmail.trim(), ApiKeys.hash(apiKey), defaultCreditLimit, clock.instant()));

        // Same transaction: a partner never exists without its position account.
        AccountView position = accountApi.openPartnerAccount(saved.getId(), defaultCreditLimit);

        return new RegisteredPartner(saved.getId(), saved.getName(), saved.getCreditLimit(),
                position.accountNumber(), apiKey);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<PartnerView> authenticate(String apiKey) {
        return partnerRepository.findByApiKeyHash(ApiKeys.hash(apiKey))
                .filter(p -> p.getStatus() == PartnerStatus.ACTIVE)
                .map(PartnerService::toView);
    }

    @Override
    @Transactional(readOnly = true)
    public PartnerView get(UUID partnerId) {
        return partnerRepository.findById(partnerId)
                .map(PartnerService::toView)
                .orElseThrow(() -> new BusinessException("PARTNER_NOT_FOUND", "No partner with id " + partnerId));
    }

    private static PartnerView toView(Partner p) {
        return new PartnerView(p.getId(), p.getName(), p.getCreditLimit());
    }
}