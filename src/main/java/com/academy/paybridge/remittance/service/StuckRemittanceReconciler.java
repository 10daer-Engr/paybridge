package com.academy.paybridge.remittance.service;

import com.academy.paybridge.remittance.api.RemittanceView;
import com.academy.paybridge.remittance.domain.Remittance;
import com.academy.paybridge.remittance.domain.RemittanceStatus;
import com.academy.paybridge.remittance.repository.RemittanceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/** Periodically asks the provider about bank payouts that have been PROCESSING for a while. */
@Component
public class StuckRemittanceReconciler {

    private static final Logger log = LoggerFactory.getLogger(StuckRemittanceReconciler.class);

    private final RemittanceRepository remittanceRepository;
    private final RemittanceService remittanceService;
    private final Clock clock;
    private final Duration minAge;
    private final Duration alertAfter;

    public StuckRemittanceReconciler(RemittanceRepository remittanceRepository,
                                     RemittanceService remittanceService,
                                     Clock clock,
                                     @Value("${paybridge.transfers.reconcile-min-age:PT30S}") Duration minAge,
                                     @Value("${paybridge.transfers.stuck-alert-after:PT1H}") Duration alertAfter) {
        this.remittanceRepository = remittanceRepository;
        this.remittanceService = remittanceService;
        this.clock = clock;
        this.minAge = minAge;
        this.alertAfter = alertAfter;
    }

    @Scheduled(fixedDelayString = "${paybridge.transfers.reconcile-interval:PT1M}",
            initialDelayString = "PT45S")
    public void run() {
        reconcileOnce();
    }

    /** Returns how many remittances left PROCESSING during this pass. */
    public int reconcileOnce() {
        Instant now = clock.instant();
        List<Remittance> stuck = remittanceRepository.findTop50ByStatusAndUpdatedAtBeforeOrderByUpdatedAtAsc(
                RemittanceStatus.PROCESSING, now.minus(minAge));

        if (!stuck.isEmpty()) {
            log.info("Reconciling {} PROCESSING remittance(s)", stuck.size());
        }

        int settled = 0;
        for (Remittance remittance : stuck) {
            try {
                RemittanceView after = remittanceService.refreshInternal(remittance.getId());
                if (!"PROCESSING".equals(after.status())) {
                    settled++;
                } else if (Duration.between(remittance.getUpdatedAt(), now).compareTo(alertAfter) > 0) {
                    log.error("Remittance {} has been PROCESSING for over {}. Needs manual attention.",
                            remittance.getId(), alertAfter);
                }
            } catch (RuntimeException e) {
                log.warn("Could not reconcile remittance {}: {}", remittance.getId(), e.getMessage());
            }
        }
        return settled;
    }
}