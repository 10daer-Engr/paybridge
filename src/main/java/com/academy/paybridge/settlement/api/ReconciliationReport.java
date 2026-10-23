package com.academy.paybridge.settlement.api;

import com.academy.paybridge.shared.money.Money;

import java.util.List;
import java.util.UUID;

public record ReconciliationReport(
        UUID runId,
        String status,                 // MATCHED or BREAKS
        int statementLines,
        Money statementTotal,
        Money ourTotal,
        List<BreakView> breaks,
        SettlementView settlement) {

    public record BreakView(String kind, String reference, Money ourAmount, Money theirAmount) {
    }
}