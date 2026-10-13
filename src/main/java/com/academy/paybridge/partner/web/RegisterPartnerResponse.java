package com.academy.paybridge.partner.web;

import java.util.UUID;

public record RegisterPartnerResponse(UUID partnerId, String name, String creditLimitUsd, String apiKey, String note) {
}