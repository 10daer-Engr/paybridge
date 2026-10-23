package com.academy.paybridge.compliance.api;

public interface ComplianceApi {

    /**
     * Screens a payment against the watch list and the daily limits. Must run inside the caller's
     * transaction: it takes locks so concurrent payments cannot slip past a shared limit, and an
     * approval lives or dies with the payment it belongs to. A refusal is recorded permanently,
     * in its own transaction, even if the caller then rolls back.
     */
    ComplianceDecision screen(ScreeningRequest request);
}