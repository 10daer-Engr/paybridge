package com.academy.paybridge.customer.api;

import java.security.Principal;
import java.util.UUID;

/** Who is making the current request. Stored in Spring Security's context by the API-key filter. */
public record AuthenticatedCustomer(UUID id, String fullName) implements Principal {

    @Override
    public String getName() {
        return "customer:" + id;
    }
}