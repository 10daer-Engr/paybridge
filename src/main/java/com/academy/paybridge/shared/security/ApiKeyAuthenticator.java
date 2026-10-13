package com.academy.paybridge.shared.security;

import org.springframework.security.core.Authentication;

import java.util.Optional;

/**
 * Implemented by each module that issues API keys. The security filter asks every
 * authenticator in turn and knows nothing about customers, partners or any future caller type.
 */
public interface ApiKeyAuthenticator {

    /** True if the key has this authenticator's prefix. No database access here. */
    boolean supports(String apiKey);

    /** The authenticated caller, or empty if the key is unknown or the caller is disabled. */
    Optional<Authentication> authenticate(String apiKey);
}