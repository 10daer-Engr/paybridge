package com.academy.paybridge.transfer.client.simulated;

import com.academy.paybridge.transfer.client.GatewayRejectedException;
import com.academy.paybridge.transfer.client.GatewayUnavailableException;
import com.academy.paybridge.transfer.client.TransferGateway;
import com.academy.paybridge.transfer.domain.SimulatedPayout;
import com.academy.paybridge.transfer.repository.SimulatedPayoutRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * A payment provider that lives inside PayBridge, for demos where the real sandbox is unavailable.
 * What happens is decided by the LAST TWO DIGITS of the recipient's account number:
 * 11 refused, 22 timeout (not processed), 33 timeout (but paid), 44 pending then success,
 * 55 pending then failure, 99 beneficiary named "IVAN SANCTIONOV", anything else succeeds.
 */
@Component
@ConditionalOnProperty(prefix = "paybridge.provider", name = "mode", havingValue = "simulated")
class SimulatedTransferGateway implements TransferGateway {

    private static final Logger log = LoggerFactory.getLogger(SimulatedTransferGateway.class);

    private static final String RECIPIENT_PREFIX = "RCP_SIM_";

    enum Scenario {
        SUCCESS,
        REJECT,
        TIMEOUT_NOT_PROCESSED,
        TIMEOUT_BUT_PROCESSED,
        PENDING_THEN_SUCCESS,
        PENDING_THEN_FAILURE
    }

    private final SimulatedPayoutRepository repository;
    private final Clock clock;
    private final long pendingSeconds;

    SimulatedTransferGateway(SimulatedPayoutRepository repository, Clock clock,
                             @Value("${paybridge.provider.simulated-pending-seconds:30}") long pendingSeconds) {
        this.repository = repository;
        this.clock = clock;
        this.pendingSeconds = pendingSeconds;
        log.warn("PAYOUT PROVIDER MODE: SIMULATED. No real payment provider is called. "
                + "Pending payouts resolve after {} seconds.", pendingSeconds);
    }

    @Override
    public List<Bank> listBanks() {
        return List.of(
                new Bank("Access Bank", "044"),
                new Bank("First Bank of Nigeria", "011"),
                new Bank("Guaranty Trust Bank", "058"),
                new Bank("United Bank for Africa", "033"),
                new Bank("Zenith Bank", "057"),
                new Bank("Simulated Test Bank", "001"));
    }

    @Override
    public ResolvedAccount resolveAccount(String accountNumber, String bankCode) {
        String name = accountNumber.endsWith("99") ? "IVAN SANCTIONOV" : "SIMULATED ACCOUNT " + accountNumber;
        return new ResolvedAccount(accountNumber, name);
    }

    @Override
    public String createRecipient(String accountName, String accountNumber, String bankCode) {
        return RECIPIENT_PREFIX + scenarioFor(accountNumber).name() + "_" + accountNumber;
    }

    @Override
    public GatewayTransferResult initiateTransfer(GatewayTransferRequest request) {
        // A real provider treats a repeated reference as the same payout. So do we.
        Optional<SimulatedPayout> existing = repository.findById(request.reference());
        if (existing.isPresent()) {
            return resultOf(existing.get(), false);
        }

        Scenario scenario = scenarioFromRecipientCode(request.recipientCode());
        long amount = request.amount().minorUnits();

        switch (scenario) {
            case REJECT:
                throw new GatewayRejectedException(422, "Simulated provider refused the payout");
            case TIMEOUT_NOT_PROCESSED:
                // Nothing is stored: the "provider" never received the request.
                throw new GatewayUnavailableException(
                        "Simulated provider did not respond (the payout was NOT processed)", null);
            case TIMEOUT_BUT_PROCESSED:
                repository.save(SimulatedPayout.of(request.reference(), scenario.name(), amount, clock.instant()));
                throw new GatewayUnavailableException(
                        "Simulated provider did not respond (the payout WAS processed)", null);
            default:
                SimulatedPayout payout = repository.save(
                        SimulatedPayout.of(request.reference(), scenario.name(), amount, clock.instant()));
                return resultOf(payout, false);
        }
    }

    @Override
    public Optional<GatewayTransferResult> verifyTransfer(String reference) {
        return repository.findById(reference).map(p -> resultOf(p, true));
    }

    // ---- helpers ----

    /** When initiating, a pending payout always answers "pending". Only later status checks see it resolve. */
    private GatewayTransferResult resultOf(SimulatedPayout payout, boolean checkingLater) {
        Scenario scenario = Scenario.valueOf(payout.getScenario());
        boolean matured = Duration.between(payout.getCreatedAt(), clock.instant()).getSeconds() >= pendingSeconds;

        GatewayTransferStatus status = switch (scenario) {
            case SUCCESS, TIMEOUT_BUT_PROCESSED -> GatewayTransferStatus.SUCCESS;
            case PENDING_THEN_SUCCESS -> checkingLater && matured ? GatewayTransferStatus.SUCCESS : GatewayTransferStatus.PENDING;
            case PENDING_THEN_FAILURE -> checkingLater && matured ? GatewayTransferStatus.FAILED : GatewayTransferStatus.PENDING;
            case REJECT, TIMEOUT_NOT_PROCESSED -> throw new IllegalStateException("Never stored: " + scenario);
        };
        String providerCode = "TRF_SIM_" + payout.getReference().substring(0, Math.min(12, payout.getReference().length()));
        return new GatewayTransferResult(payout.getReference(), providerCode, status, "simulated");
    }

    private static Scenario scenarioFor(String accountNumber) {
        if (accountNumber == null || accountNumber.length() < 2) {
            return Scenario.SUCCESS;
        }
        return switch (accountNumber.substring(accountNumber.length() - 2)) {
            case "11" -> Scenario.REJECT;
            case "22" -> Scenario.TIMEOUT_NOT_PROCESSED;
            case "33" -> Scenario.TIMEOUT_BUT_PROCESSED;
            case "44" -> Scenario.PENDING_THEN_SUCCESS;
            case "55" -> Scenario.PENDING_THEN_FAILURE;
            default -> Scenario.SUCCESS;
        };
    }

    private static Scenario scenarioFromRecipientCode(String recipientCode) {
        // "RCP_SIM_<SCENARIO>_<account number>"
        String body = recipientCode.startsWith(RECIPIENT_PREFIX)
                ? recipientCode.substring(RECIPIENT_PREFIX.length())
                : "SUCCESS_0";
        return Scenario.valueOf(body.substring(0, body.lastIndexOf('_')));
    }
}