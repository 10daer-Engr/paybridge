package com.academy.paybridge.compliance.api;

import java.util.List;

/**
 * The reasons are for PayBridge's own records. Only publicMessage may be shown to the partner:
 * telling someone they matched a watch list would "tip them off".
 */
public record ComplianceDecision(ComplianceOutcome outcome, List<String> reasons, String publicMessage) {

    public static ComplianceDecision approved() {
        return new ComplianceDecision(ComplianceOutcome.APPROVED, List.of(), "");
    }
}