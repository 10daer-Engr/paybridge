package com.academy.paybridge.remittance.web;

import com.academy.paybridge.remittance.api.RemittanceResult;
import com.academy.paybridge.remittance.api.RemittanceView;

import java.time.Instant;
import java.util.UUID;

public record RemittanceResponse(
        UUID id,
        String status,
        String partnerReference,
        UUID quoteId,
        String senderName,
        String senderCountry,
        String recipientType,
        String recipientAccountNumber,
        String recipientBankCode,
        String recipientName,
        String sendAmount,
        String fee,
        String receiveAmount,
        String customerRate,
        String failureReason,
        Instant createdAt,
        Instant completedAt,
        boolean replayed) {

    static RemittanceResponse from(RemittanceResult result) {
        return from(result.remittance(), result.replayed());
    }

    static RemittanceResponse from(RemittanceView v, boolean replayed) {
        return new RemittanceResponse(
                v.id(), v.status(), v.partnerReference(), v.quoteId(),
                v.senderName(), v.senderCountry(), v.recipientType(),
                v.recipientAccountNumber(), v.recipientBankCode(), v.recipientName(),
                v.sendAmount().toMajor().toPlainString(),
                v.fee().toMajor().toPlainString(),
                v.receiveAmount().toMajor().toPlainString(),
                v.customerRate().toPlainString(),
                v.failureReason(), v.createdAt(), v.completedAt(), replayed);
    }
}