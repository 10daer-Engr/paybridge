package com.academy.paybridge.remittance.service;

import com.academy.paybridge.transfer.api.PayoutEvent;
import com.academy.paybridge.transfer.api.PayoutEventHandler;
import com.academy.paybridge.transfer.api.PayoutEventResult;
import org.springframework.stereotype.Component;

@Component
public class RemittancePayoutEventHandler implements PayoutEventHandler {

    private static final String PREFIX = "rem_";

    private final RemittanceService remittanceService;

    public RemittancePayoutEventHandler(RemittanceService remittanceService) {
        this.remittanceService = remittanceService;
    }

    @Override
    public boolean supports(String providerReference) {
        return providerReference != null && providerReference.startsWith(PREFIX);
    }

    @Override
    public PayoutEventResult handle(PayoutEvent event) {
        return remittanceService.applyProviderUpdate(event);
    }
}