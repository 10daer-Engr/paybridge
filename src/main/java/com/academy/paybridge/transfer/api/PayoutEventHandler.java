package com.academy.paybridge.transfer.api;

/**
 * Implemented by any module that owns payouts. The webhook service finds every implementation,
 * so it never needs to know which modules exist.
 */
public interface PayoutEventHandler {

    boolean supports(String providerReference);

    PayoutEventResult handle(PayoutEvent event);
}