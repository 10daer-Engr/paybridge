package com.academy.paybridge.partner.api;

import java.security.Principal;
import java.util.UUID;

public record AuthenticatedPartner(UUID id, String name) implements Principal {

    @Override
    public String getName() {
        return "partner:" + id;
    }

    public UUID partnerId() {
        return id;
    }

    public String partnerName() {
        return name;
    }
}