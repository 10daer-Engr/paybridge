package com.academy.paybridge.partner.api;

import java.util.Optional;
import java.util.UUID;

public interface PartnerApi {

    /** Every partner API key starts with this. */
    String API_KEY_PREFIX = "pbp_";

    RegisteredPartner register(String name, String contactEmail);

    /** Empty if the key is unknown or the partner is suspended. */
    Optional<PartnerView> authenticate(String apiKey);

    PartnerView get(UUID partnerId);
}