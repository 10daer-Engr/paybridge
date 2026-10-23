package com.academy.paybridge.settlement.api;

import java.util.List;
import java.util.UUID;

public interface SettlementApi {

    /** Claims every completed, not-yet-settled remittance of the partner into a new batch. */
    SettlementView generate(UUID partnerId);

    List<SettlementView> list(UUID partnerId);

    SettlementDetail detail(UUID partnerId, UUID batchId);

    /** Records that the partner's dollars arrived, and posts it to the ledger. */
    PaymentView recordPayment(RecordPaymentCommand command);

    /** Compares the partner's own statement with our batch and records the result. May be repeated. */
    ReconciliationReport reconcile(UUID partnerId, UUID batchId, List<StatementLine> statement);
}