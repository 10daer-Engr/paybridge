package com.academy.paybridge.partner.web;

import com.academy.paybridge.partner.api.CurrentPartner;
import com.academy.paybridge.partner.api.PartnerApi;
import com.academy.paybridge.partner.api.PartnerView;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/partner")
@Tag(name = "Partner")
public class PartnerController {

    private final PartnerApi partnerApi;

    public PartnerController(PartnerApi partnerApi) {
        this.partnerApi = partnerApi;
    }

    @GetMapping("/me")
    @Operation(summary = "Who am I? Shows the partner this key belongs to and its credit limit")
    public Map<String, Object> me() {
        PartnerView partner = partnerApi.get(CurrentPartner.id());
        return Map.of(
                "partnerId", partner.id(),
                "name", partner.name(),
                "creditLimitUsd", partner.creditLimit().toMajor().toPlainString());
    }
}