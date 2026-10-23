package com.academy.paybridge.transfer.service;

import com.academy.paybridge.transfer.api.PayoutApi;
import com.academy.paybridge.transfer.api.PayoutRequest;
import com.academy.paybridge.transfer.api.PayoutResult;
import com.academy.paybridge.transfer.api.PayoutStatus;
import com.academy.paybridge.transfer.api.PreparedRecipient;
import com.academy.paybridge.transfer.client.GatewayRejectedException;
import com.academy.paybridge.transfer.client.GatewayUnavailableException;
import com.academy.paybridge.transfer.client.TransferGateway;
import com.academy.paybridge.transfer.client.TransferGateway.GatewayTransferRequest;
import com.academy.paybridge.transfer.client.TransferGateway.GatewayTransferResult;
import com.academy.paybridge.transfer.client.TransferGateway.GatewayTransferStatus;
import com.academy.paybridge.transfer.client.TransferGateway.ResolvedAccount;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
public class BankPayoutService implements PayoutApi {

    private final TransferGateway gateway;

    public BankPayoutService(TransferGateway gateway) {
        this.gateway = gateway;
    }

    @Override
    public PreparedRecipient prepareRecipient(String accountNumber, String bankCode) {
        ResolvedAccount resolved = gateway.resolveAccount(accountNumber, bankCode);
        String recipientCode = gateway.createRecipient(resolved.accountName(), accountNumber, bankCode);
        return new PreparedRecipient(recipientCode, resolved.accountName());
    }

    @Override
    public PayoutResult initiate(PayoutRequest request) {
        try {
            GatewayTransferResult result = gateway.initiateTransfer(new GatewayTransferRequest(
                    request.reference(), request.recipientCode(), request.amount(), request.reason()));
            return new PayoutResult(result.reference(), result.providerCode(), map(result.status()), result.message());
        } catch (GatewayRejectedException e) {
            return new PayoutResult(request.reference(), null, PayoutStatus.REJECTED, e.getMessage());
        } catch (GatewayUnavailableException e) {
            return new PayoutResult(request.reference(), null, PayoutStatus.UNKNOWN, e.getMessage());
        }
    }

    @Override
    public Optional<PayoutResult> verify(String reference) {
        return gateway.verifyTransfer(reference)
                .map(r -> new PayoutResult(r.reference(), r.providerCode(), map(r.status()), r.message()));
    }

    private static PayoutStatus map(GatewayTransferStatus status) {
        return switch (status) {
            case SUCCESS -> PayoutStatus.SUCCESS;
            case PENDING -> PayoutStatus.PENDING;
            case FAILED -> PayoutStatus.FAILED;
            case REVERSED -> PayoutStatus.REVERSED;
            case ACTION_REQUIRED -> PayoutStatus.ACTION_REQUIRED;
        };
    }
}