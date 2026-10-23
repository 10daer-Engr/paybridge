package com.academy.paybridge.transfer.api;

public enum PayoutStatus {
    SUCCESS,
    PENDING,
    FAILED,
    REVERSED,
    ACTION_REQUIRED,   // the provider wants a human step such as an OTP
    REJECTED,          // the provider refused the request outright: nothing was sent
    UNKNOWN            // no reliable answer: it may or may not have been sent
}