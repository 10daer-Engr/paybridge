package com.academy.paybridge.transfer.api;

public record PayoutResult(String reference, String providerCode, PayoutStatus status, String message) {
}