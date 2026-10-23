package com.academy.paybridge.compliance.service;

import com.academy.paybridge.compliance.api.ComplianceApi;
import com.academy.paybridge.compliance.api.ComplianceDecision;
import com.academy.paybridge.compliance.api.ComplianceOutcome;
import com.academy.paybridge.compliance.api.ScreeningRequest;
import com.academy.paybridge.compliance.config.ComplianceProperties;
import com.academy.paybridge.compliance.domain.ComplianceCheck;
import com.academy.paybridge.compliance.domain.NameMatcher;
import com.academy.paybridge.compliance.repository.ComplianceCheckRepository;
import com.academy.paybridge.shared.money.Currency;
import com.academy.paybridge.shared.money.Money;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.TreeSet;

@Service
public class ComplianceService implements ComplianceApi {

    private static final String GENERIC_MESSAGE = "This payment cannot be processed.";
    private static final String LIMIT_MESSAGE = "A daily limit has been reached for this sender, recipient or partner.";
    private static final String NAME_MESSAGE = "The sender name is not valid.";

    private final ComplianceCheckRepository repository;
    private final ComplianceProperties properties;
    private final Clock clock;
    private final TransactionTemplate independentTransaction;

    public ComplianceService(ComplianceCheckRepository repository,
                             ComplianceProperties properties,
                             Clock clock,
                             PlatformTransactionManager transactionManager) {
        this.repository = repository;
        this.properties = properties;
        this.clock = clock;
        // A template, not @Transactional(REQUIRES_NEW): calling a proxied method of our own class
        // would skip the proxy, so no new transaction would start.
        this.independentTransaction = new TransactionTemplate(transactionManager);
        this.independentTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public ComplianceDecision screen(ScreeningRequest request) {
        Instant now = clock.instant();
        String normalizedName = NameMatcher.normalize(request.senderName());
        String country = request.senderCountry().toUpperCase(Locale.ROOT);
        String fingerprint = fingerprint(normalizedName, country);

        // Limits are check-then-act, so concurrent payments sharing a sender, recipient or partner must queue.
        // Always lock in the same (sorted) order so two payments can never wait on each other.
        lockInOrder(List.of(
                "partner:" + request.partnerId(),
                "sender:" + fingerprint,
                "recipient:" + request.recipientRef()));

        List<String> hard = new ArrayList<>();
        List<String> soft = new ArrayList<>();
        screenSender(request.senderName(), normalizedName, country, hard, soft);
        screenRecipientName(request.recipientName(), hard, soft);
        checkLimits(request, fingerprint, now, hard);

        ComplianceDecision decision = decide(hard, soft);
        ComplianceCheck record = ComplianceCheck.of(
                request.partnerId(), request.subjectRef(), decision.outcome(),
                String.join(",", decision.reasons()), request.senderName(), country, fingerprint,
                request.recipientRef(), request.amount().minorUnits(), now);

        if (decision.outcome() == ComplianceOutcome.APPROVED) {
            repository.save(record);   // joins the payment's transaction: if the payment fails, so does this
        } else {
            // Its own transaction: the refusal stays on record even after the payment is rolled back.
            independentTransaction.executeWithoutResult(status -> repository.save(record));
        }
        return decision;
    }

    // ---- rules ----

    private void screenSender(String rawName, String normalizedName, String country,
                              List<String> hard, List<String> soft) {
        if (normalizedName.isBlank()) {
            hard.add("SENDER_NAME_INVALID");
            return;
        }
        if (properties.blockedCountries().stream().anyMatch(c -> c.equalsIgnoreCase(country))) {
            hard.add("SENDER_COUNTRY_BLOCKED");
        }
        for (String listed : properties.blockedNames()) {
            switch (NameMatcher.match(rawName, listed, properties.matchThreshold())) {
                case EXACT -> hard.add("SANCTIONS_MATCH");
                case POSSIBLE -> soft.add("SANCTIONS_POSSIBLE_MATCH");
                case NONE -> { }
            }
        }
    }

    private void screenRecipientName(String recipientName, List<String> hard, List<String> soft) {
        if (recipientName == null || recipientName.isBlank()) {
            return;
        }
        for (String listed : properties.blockedNames()) {
            switch (NameMatcher.match(recipientName, listed, properties.matchThreshold())) {
                case EXACT -> hard.add("SANCTIONS_RECIPIENT_MATCH");
                case POSSIBLE -> soft.add("SANCTIONS_POSSIBLE_RECIPIENT_MATCH");
                case NONE -> { }
            }
        }
    }

    private void checkLimits(ScreeningRequest request, String fingerprint, Instant now, List<String> hard) {
        Instant since = now.minus(properties.window());
        long amount = request.amount().minorUnits();

        if (exceeds(repository.approvedAmountBySender(fingerprint, since), amount,
                properties.senderDailyLimitUsd())) {
            hard.add("SENDER_DAILY_LIMIT_EXCEEDED");
        }
        if (exceeds(repository.approvedAmountByRecipient(request.recipientRef(), since), amount,
                properties.recipientDailyLimitUsd())) {
            hard.add("RECIPIENT_DAILY_LIMIT_EXCEEDED");
        }
        if (exceeds(repository.approvedAmountByPartner(request.partnerId(), since), amount,
                properties.partnerDailyLimitUsd())) {
            hard.add("PARTNER_DAILY_LIMIT_EXCEEDED");
        }
    }

    private static boolean exceeds(long alreadyApproved, long amount, BigDecimal limitUsd) {
        long limitMinor = Money.ofMajor(limitUsd, Currency.USD).minorUnits();
        return Math.addExact(alreadyApproved, amount) > limitMinor;
    }

    private static ComplianceDecision decide(List<String> hard, List<String> soft) {
        if (hard.isEmpty() && soft.isEmpty()) {
            return ComplianceDecision.approved();
        }
        List<String> reasons = new ArrayList<>(hard);
        reasons.addAll(soft);

        ComplianceOutcome outcome = hard.isEmpty() ? ComplianceOutcome.REFERRED : ComplianceOutcome.REJECTED;

        boolean sensitive = reasons.stream()
                .anyMatch(r -> r.startsWith("SANCTIONS_") || r.equals("SENDER_COUNTRY_BLOCKED"));
        boolean limit = reasons.stream().anyMatch(r -> r.endsWith("_DAILY_LIMIT_EXCEEDED"));

        // Never reveal a watch-list or country hit. Limits are not sensitive, so they can be explained.
        String message = sensitive ? GENERIC_MESSAGE : (limit ? LIMIT_MESSAGE : NAME_MESSAGE);
        return new ComplianceDecision(outcome, List.copyOf(reasons), message);
    }

    // ---- helpers ----

    private void lockInOrder(List<String> keys) {
        for (String key : new TreeSet<>(keys)) {
            repository.lockKey(key);
        }
    }

    /** The same person gives the same fingerprint however the name is written. */
    private static String fingerprint(String normalizedName, String country) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            String input = NameMatcher.sortedTokens(normalizedName) + "|" + country;
            return HexFormat.of().formatHex(digest.digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}