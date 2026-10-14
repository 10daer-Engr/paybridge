package com.academy.paybridge.partner.web;

import com.academy.paybridge.account.api.AccountApi;
import com.academy.paybridge.account.api.AccountView;
import com.academy.paybridge.partner.api.CurrentPartner;
import com.academy.paybridge.partner.api.PartnerApi;
import com.academy.paybridge.partner.api.PartnerView;
import com.academy.paybridge.shared.money.Currency;
import com.academy.paybridge.shared.money.Money;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/partner")
@Tag(name = "Partner")
public class PartnerController {

    private final PartnerApi partnerApi;
    private final AccountApi accountApi;

    public PartnerController(PartnerApi partnerApi, AccountApi accountApi) {
        this.partnerApi = partnerApi;
        this.accountApi = accountApi;
    }

    @GetMapping("/me")
    @Operation(summary = "Who am I? Shows the partner, its credit limit and how much it currently owes")
    public Map<String, Object> me() {
        PartnerView partner = partnerApi.get(CurrentPartner.id());
        AccountView position = accountApi.partnerAccount(partner.id(), Currency.USD);

        // A negative position balance means the partner owes PayBridge.
        Money owed = new Money(Math.max(0, -position.balance().minorUnits()), Currency.USD);
        Money available = partner.creditLimit().minus(owed);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("partnerId", partner.id());
        body.put("name", partner.name());
        body.put("usdAccountNumber", position.accountNumber());
        body.put("creditLimitUsd", partner.creditLimit().toMajor().toPlainString());
        body.put("owedUsd", owed.toMajor().toPlainString());
        body.put("availableCreditUsd", available.toMajor().toPlainString());
        return body;
    }
}