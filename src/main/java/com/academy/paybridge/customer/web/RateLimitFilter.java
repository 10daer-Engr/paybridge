package com.academy.paybridge.customer.web;

import com.academy.paybridge.customer.api.AuthenticatedCustomer;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import java.security.Principal;

import java.io.IOException;
import java.time.Duration;

/** Runs after authentication, so logged-in callers are limited per customer and others per address. */
class RateLimitFilter extends OncePerRequestFilter {

    private record Policy(String name, int limit, Duration window) {
    }

    private static final Policy GENERAL = new Policy("general", 120, Duration.ofMinutes(1));
    private static final Policy MONEY = new Policy("money", 20, Duration.ofMinutes(1));
    private static final Policy LOOKUP = new Policy("lookup", 20, Duration.ofMinutes(1));
    private static final Policy REGISTER_PER_ADDRESS = new Policy("register-address", 5, Duration.ofHours(1));
    private static final Policy REGISTER_GLOBAL = new Policy("register-all", 100, Duration.ofHours(1));

    private final RateLimiter limiter;

    RateLimitFilter(RateLimiter limiter) {
        this.limiter = limiter;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path.startsWith("/swagger-ui")
                || path.startsWith("/v3/api-docs")
                || path.startsWith("/actuator/")
                || path.equals("/api/v1/webhooks/paystack");   // protected by its signature instead
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String identity = identityOf(request);

        boolean allowed;
        if (isRegistration(request)) {
            allowed = permit(response, REGISTER_PER_ADDRESS, identity)
                    && permit(response, REGISTER_GLOBAL, "all");
        } else {
            allowed = permit(response, policyFor(request), identity);
        }

        if (allowed) {
            chain.doFilter(request, response);
        }
    }

    private static boolean isRegistration(HttpServletRequest request) {
        String path = request.getRequestURI();
        return "POST".equals(request.getMethod())
                && (path.equals("/api/v1/customers/register") || path.equals("/api/v1/partner/register"));
    }

    private static Policy policyFor(HttpServletRequest request) {
        String path = request.getRequestURI();
        boolean post = "POST".equals(request.getMethod());

        if (post && (path.startsWith("/api/v1/transfers")
                || path.startsWith("/api/v1/sandbox/")
                || path.startsWith("/api/v1/partner/")
                || path.equals("/api/v1/accounts"))) {
            return MONEY;
        }
        if (path.startsWith("/api/v1/banks")) {
            return LOOKUP;   // each call costs a request to the payment provider
        }
        return GENERAL;
    }

    private static String identityOf(HttpServletRequest request) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof Principal principal) {
            return principal.getName();   // "customer:<id>" or "partner:<id>"
        }
        return "address:" + request.getRemoteAddr();
    }

    private boolean permit(HttpServletResponse response, Policy policy, String identity) throws IOException {
        RateLimiter.Decision decision =
                limiter.tryAcquire(policy.name() + ":" + identity, policy.limit(), policy.window());
        if (decision.allowed()) {
            return true;
        }
        response.setStatus(429);
        response.setHeader("Retry-After", String.valueOf(decision.retryAfterSeconds()));
        response.setContentType("application/problem+json");
        response.getWriter().write("{\"type\":\"about:blank\",\"title\":\"Too Many Requests\",\"status\":429,"
                + "\"detail\":\"Rate limit exceeded. Try again in " + decision.retryAfterSeconds()
                + " seconds.\",\"code\":\"RATE_LIMITED\"}");
        return false;
    }
}