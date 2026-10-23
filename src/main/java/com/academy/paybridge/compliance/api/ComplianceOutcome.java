package com.academy.paybridge.compliance.api;

public enum ComplianceOutcome {
    APPROVED,
    REJECTED,   // a definite hit: the payment must not proceed
    REFERRED    // a possible match: needs a human analyst. The payment does not proceed for now.
}