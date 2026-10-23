package com.academy.paybridge.transfer.api;

import java.util.Optional;

/** Pays a bank account from PayBridge's own provider balance. Used by modules that are not customer transfers. */
public interface PayoutApi {

    /** Name enquiry plus recipient registration. Throws if the provider refuses or cannot be reached. */
    PreparedRecipient prepareRecipient(String accountNumber, String bankCode);

    /** Never throws for provider problems: they come back as REJECTED or UNKNOWN. */
    PayoutResult initiate(PayoutRequest request);

    /** Empty means the provider has no record of that reference. */
    Optional<PayoutResult> verify(String reference);
}