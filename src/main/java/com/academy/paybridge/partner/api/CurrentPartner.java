package com.academy.paybridge.partner.api;

import com.academy.paybridge.shared.exception.BusinessException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.UUID;

public final class CurrentPartner {

    private CurrentPartner() {
    }

    public static UUID id() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof AuthenticatedPartner partner) {
            return partner.id();
        }
        throw new BusinessException("UNAUTHENTICATED", "Partner authentication is required");
    }
}