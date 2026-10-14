package com.academy.paybridge.partner.web;

import com.academy.paybridge.partner.api.PartnerApi;
import com.academy.paybridge.partner.api.RegisteredPartner;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/partner")
@Tag(name = "Partner")
@ConditionalOnProperty(prefix = "paybridge.sandbox", name = "partner-registration-enabled", havingValue = "true")
public class PartnerRegistrationController {

    private final PartnerApi partnerApi;

    public PartnerRegistrationController(PartnerApi partnerApi) {
        this.partnerApi = partnerApi;
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Sandbox only: register a sending partner and receive its API key",
            description = "The key starts with pbp_ and is shown ONCE. Use Authorize to send it as X-API-Key. "
                    + "A partner key can only use /partner endpoints, and a customer key cannot.")
    public RegisterPartnerResponse register(@Valid @RequestBody RegisterPartnerRequest request) {
        RegisteredPartner registered = partnerApi.register(request.name(), request.contactEmail());
        return new RegisterPartnerResponse(
                registered.partnerId(),
                registered.name(),
                registered.creditLimit().toMajor().toPlainString(),
                registered.usdAccountNumber(),
                registered.apiKey(),
                "Store this key now. It cannot be shown again.");
    }
}