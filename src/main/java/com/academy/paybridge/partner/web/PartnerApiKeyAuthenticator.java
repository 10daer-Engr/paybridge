package com.academy.paybridge.partner.web;

import com.academy.paybridge.partner.api.AuthenticatedPartner;
import com.academy.paybridge.partner.api.PartnerApi;
import com.academy.paybridge.shared.security.ApiKeyAuthenticator;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

@Component
class PartnerApiKeyAuthenticator implements ApiKeyAuthenticator {

    private final PartnerApi partnerApi;

    PartnerApiKeyAuthenticator(PartnerApi partnerApi) {
        this.partnerApi = partnerApi;
    }

    @Override
    public boolean supports(String apiKey) {
        return apiKey.startsWith(PartnerApi.API_KEY_PREFIX);
    }

    @Override
    public Optional<Authentication> authenticate(String apiKey) {
        return partnerApi.authenticate(apiKey).<Authentication>map(partner ->
                new UsernamePasswordAuthenticationToken(
                        new AuthenticatedPartner(partner.id(), partner.name()),
                        null,
                        List.of(new SimpleGrantedAuthority("ROLE_PARTNER"))));
    }
}