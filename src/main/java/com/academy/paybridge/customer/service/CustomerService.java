package com.academy.paybridge.customer.service;

import com.academy.paybridge.customer.api.CustomerApi;
import com.academy.paybridge.customer.api.CustomerView;
import com.academy.paybridge.customer.api.RegisteredCustomer;
import com.academy.paybridge.customer.domain.Customer;
import com.academy.paybridge.customer.domain.CustomerStatus;
import com.academy.paybridge.customer.repository.CustomerRepository;
import com.academy.paybridge.shared.exception.BusinessException;
import com.academy.paybridge.shared.security.ApiKeys;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.Locale;
import java.util.Optional;

@Service
public class CustomerService implements CustomerApi {

    private final CustomerRepository customerRepository;
    private final Clock clock;

    public CustomerService(CustomerRepository customerRepository, Clock clock) {
        this.customerRepository = customerRepository;
        this.clock = clock;
    }

    @Override
    @Transactional
    public RegisteredCustomer register(String fullName, String email) {
        String normalizedEmail = email.trim().toLowerCase(Locale.ROOT);
        if (customerRepository.existsByEmailIgnoreCase(normalizedEmail)) {
            throw new BusinessException("EMAIL_ALREADY_REGISTERED", "That email is already registered");
        }

        String apiKey = ApiKeys.generate(CustomerApi.API_KEY_PREFIX);
        Customer saved = customerRepository.save(
                Customer.register(fullName.trim(), normalizedEmail, ApiKeys.hash(apiKey), clock.instant()));

        return new RegisteredCustomer(saved.getId(), saved.getFullName(), apiKey);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<CustomerView> authenticate(String apiKey) {
        return customerRepository.findByApiKeyHash(ApiKeys.hash(apiKey))
                .filter(c -> c.getStatus() == CustomerStatus.ACTIVE)
                .map(c -> new CustomerView(c.getId(), c.getFullName()));
    }
}