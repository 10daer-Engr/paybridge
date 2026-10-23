package com.academy.paybridge.remittance.api;

import java.util.List;
import java.util.UUID;

public interface RemittanceApi {

    RemittanceResult create(CreateRemittanceCommand command);

    /** Only the owning partner can see a remittance. Others get REMITTANCE_NOT_FOUND. */
    RemittanceView get(UUID remittanceId, UUID partnerId);

    /** The partner's 20 most recent remittances, newest first. */
    List<RemittanceView> recent(UUID partnerId);

    /** Asks the provider what happened to a PROCESSING bank payout and settles it. Safe to repeat. */
    RemittanceView refresh(UUID remittanceId, UUID partnerId);
}