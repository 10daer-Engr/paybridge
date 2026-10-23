package com.academy.paybridge.settlement.web;

import com.academy.paybridge.partner.api.CurrentPartner;
import com.academy.paybridge.settlement.api.SettlementApi;
import com.academy.paybridge.settlement.api.StatementLine;
import com.academy.paybridge.settlement.web.SettlementDtos.DetailResponse;
import com.academy.paybridge.settlement.web.SettlementDtos.ReconcileRequest;
import com.academy.paybridge.settlement.web.SettlementDtos.ReconciliationResponse;
import com.academy.paybridge.settlement.web.SettlementDtos.SettlementResponse;
import com.academy.paybridge.shared.money.Currency;
import com.academy.paybridge.shared.money.Money;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/partner/settlements")
@Tag(name = "Partner Settlements")
public class SettlementController {

    private final SettlementApi settlementApi;

    public SettlementController(SettlementApi settlementApi) {
        this.settlementApi = settlementApi;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create a settlement batch from your completed, not-yet-settled remittances",
            description = "Each remittance is settled exactly once. Reversed or still-processing remittances are "
                    + "not included, and a processing one that completes later lands in your next batch.")
    public SettlementResponse generate() {
        return SettlementResponse.from(settlementApi.generate(CurrentPartner.id()));
    }

    @GetMapping
    @Operation(summary = "Your settlement batches, newest first")
    public List<SettlementResponse> list() {
        return settlementApi.list(CurrentPartner.id()).stream().map(SettlementResponse::from).toList();
    }

    @GetMapping("/{id}")
    @Operation(summary = "A settlement batch and every line in it (the statement to reconcile against)")
    public DetailResponse detail(@PathVariable UUID id) {
        return DetailResponse.from(settlementApi.detail(CurrentPartner.id(), id));
    }

    @PostMapping("/{id}/reconciliation")
    @Operation(summary = "Submit your own statement and compare it with ours",
            description = "Lines are matched by reference. Any difference is reported as a break. "
                    + "A batch closes only when it is fully paid AND its latest statement matches exactly. "
                    + "You can submit again as often as needed until it matches.")
    public ReconciliationResponse reconcile(@PathVariable UUID id, @Valid @RequestBody ReconcileRequest request) {
        List<StatementLine> lines = request.lines().stream()
                .map(l -> new StatementLine(l.reference(), Money.ofMajor(l.amount(), Currency.USD)))
                .toList();
        return ReconciliationResponse.from(settlementApi.reconcile(CurrentPartner.id(), id, lines));
    }
}