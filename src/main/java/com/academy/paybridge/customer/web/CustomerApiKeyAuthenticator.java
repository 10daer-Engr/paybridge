package com.academy.paybridge.customer.web;

import com.academy.paybridge.customer.api.AuthenticatedCustomer;
import com.academy.paybridge.customer.api.CustomerApi;
import com.academy.paybridge.shared.security.ApiKeyAuthenticator;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

@Component
class CustomerApiKeyAuthenticator implements ApiKeyAuthenticator {

    private final CustomerApi customerApi;

    CustomerApiKeyAuthenticator(CustomerApi customerApi) {
        this.customerApi = customerApi;
    }

    @Override
    public boolean supports(String apiKey) {
        return apiKey.startsWith(CustomerApi.API_KEY_PREFIX);
    }

    @Override
    public Optional<Authentication> authenticate(String apiKey) {
        return customerApi.authenticate(apiKey).<Authentication>map(customer ->
                new UsernamePasswordAuthenticationToken(
                        new AuthenticatedCustomer(customer.id(), customer.fullName()),
                        null,
                        List.of(new SimpleGrantedAuthority("ROLE_CUSTOMER"))));
    }
}