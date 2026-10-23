package com.academy.paybridge.transfer.api;

/** A status the provider reported by webhook. */
public record PayoutEvent(String providerReference, PayoutStatus status, String providerCode, Long amountMinor) {
}
