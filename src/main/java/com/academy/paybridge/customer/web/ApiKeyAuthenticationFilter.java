package com.academy.paybridge.customer.web;

import com.academy.paybridge.shared.security.ApiKeyAuthenticator;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

/**
 * Reads X-API-Key and hands it to whichever module recognises it.
 * Deliberately NOT a @Component: SecurityConfig creates the one instance we want.
 */
class ApiKeyAuthenticationFilter extends OncePerRequestFilter {

    static final String HEADER = "X-API-Key";

    private final List<ApiKeyAuthenticator> authenticators;
    private final AuthenticationEntryPoint entryPoint;

    ApiKeyAuthenticationFilter(List<ApiKeyAuthenticator> authenticators, AuthenticationEntryPoint entryPoint) {
        this.authenticators = authenticators;
        this.entryPoint = entryPoint;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String header = request.getHeader(HEADER);

        if (header != null && !header.isBlank()) {
            String apiKey = header.trim();

            Optional<Authentication> authentication = authenticators.stream()
                    .filter(a -> a.supports(apiKey))
                    .findFirst()
                    .flatMap(a -> a.authenticate(apiKey));

            if (authentication.isEmpty()) {
                // Unknown prefix, unknown key and disabled caller all look the same from outside.
                SecurityContextHolder.clearContext();
                entryPoint.commence(request, response, new BadCredentialsException("Invalid API key"));
                return;
            }

            SecurityContext context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(authentication.get());
            SecurityContextHolder.setContext(context);
        }

        chain.doFilter(request, response);
    }
}