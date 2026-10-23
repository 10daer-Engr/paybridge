package com.academy.paybridge.transfer;

import com.academy.paybridge.transfer.client.GatewayRejectedException;
import com.academy.paybridge.transfer.client.GatewayUnavailableException;
import com.academy.paybridge.transfer.client.TransferGateway;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/** A provider we can make misbehave on demand. */
public class FakeTransferGateway implements TransferGateway {

    public enum Mode {
        SUCCEED,                 // provider sends the money and says so
        REJECT,                  // provider refuses outright (4xx)
        TIMEOUT_NOT_PROCESSED,   // we hear nothing, and the provider never got the request
        TIMEOUT_BUT_PROCESSED,   // we hear nothing, but the provider DID send the money
        ACCEPT_PENDING           // provider accepts and says "pending"
    }

    private volatile Mode mode = Mode.SUCCEED;
    private final Map<String, GatewayTransferStatus> providerRecords = new ConcurrentHashMap<>();
    private final AtomicInteger initiateCalls = new AtomicInteger();
    private volatile String resolvedName = "TEST ACCOUNT HOLDER";

    public void reset() {
        mode = Mode.SUCCEED;
        providerRecords.clear();
        initiateCalls.set(0);
        resolvedName = "TEST ACCOUNT HOLDER";
    }

    public void setMode(Mode mode) {
        this.mode = mode;
    }

    /** Simulates the provider finishing a pending transfer on its own side. */
    public void settleAtProvider(String reference, GatewayTransferStatus status) {
        providerRecords.put(reference, status);
    }

    public int initiateCalls() {
        return initiateCalls.get();
    }

    @Override
    public List<Bank> listBanks() {
        return List.of(new Bank("Fake Bank", "058"));
    }

    public void setResolvedName(String resolvedName) {
        this.resolvedName = resolvedName;
    }

    @Override
    public ResolvedAccount resolveAccount(String accountNumber, String bankCode) {
        return new ResolvedAccount(accountNumber, resolvedName);
    }
    @Override
    public String createRecipient(String accountName, String accountNumber, String bankCode) {
        return "RCP_" + accountNumber;
    }

    @Override
    public GatewayTransferResult initiateTransfer(GatewayTransferRequest request) {
        initiateCalls.incrementAndGet();
        String ref = request.reference();
        switch (mode) {
            case SUCCEED -> {
                providerRecords.put(ref, GatewayTransferStatus.SUCCESS);
                return new GatewayTransferResult(ref, "TRF_fake", GatewayTransferStatus.SUCCESS, "ok");
            }
            case REJECT -> throw new GatewayRejectedException(400, "Recipient specified is invalid");
            case TIMEOUT_NOT_PROCESSED -> throw new GatewayUnavailableException("simulated timeout", null);
            case TIMEOUT_BUT_PROCESSED -> {
                providerRecords.put(ref, GatewayTransferStatus.SUCCESS);
                throw new GatewayUnavailableException("simulated timeout after processing", null);
            }
            case ACCEPT_PENDING -> {
                providerRecords.put(ref, GatewayTransferStatus.PENDING);
                return new GatewayTransferResult(ref, "TRF_fake", GatewayTransferStatus.PENDING, "queued");
            }
        }
        throw new IllegalStateException("unreachable");
    }

    @Override
    public Optional<GatewayTransferResult> verifyTransfer(String reference) {
        return Optional.ofNullable(providerRecords.get(reference))
                .map(status -> new GatewayTransferResult(reference, "TRF_fake", status, "verified"));
    }

}