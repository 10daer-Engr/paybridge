package com.academy.paybridge.partner.web;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RegisterPartnerRequest(
        @Schema(example = "Acme Remit Ltd") @NotBlank @Size(max = 150) String name,
        @Schema(example = "ops@acmeremit.example") @NotBlank @Email @Size(max = 254) String contactEmail) {
}