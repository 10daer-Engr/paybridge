package com.academy.paybridge.remittance.api;

public record RemittanceResult(RemittanceView remittance, boolean replayed) {
}