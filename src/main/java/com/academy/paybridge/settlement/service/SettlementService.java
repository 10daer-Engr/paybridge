package com.academy.paybridge.settlement.service;

import com.academy.paybridge.account.api.AccountApi;
import com.academy.paybridge.account.api.AccountView;
import com.academy.paybridge.account.api.SystemAccounts;
import com.academy.paybridge.ledger.api.LedgerApi;
import com.academy.paybridge.ledger.api.LedgerTransactionType;
import com.academy.paybridge.ledger.api.PostingLine;
import com.academy.paybridge.ledger.api.PostingRequest;
import com.academy.paybridge.ledger.api.PostingResult;
import com.academy.paybridge.settlement.api.PaymentView;
import com.academy.paybridge.settlement.api.ReconciliationReport;
import com.academy.paybridge.settlement.api.ReconciliationReport.BreakView;
import com.academy.paybridge.settlement.api.RecordPaymentCommand;
import com.academy.paybridge.settlement.api.SettlementApi;
import com.academy.paybridge.settlement.api.SettlementDetail;
import com.academy.paybridge.settlement.api.SettlementItemView;
import com.academy.paybridge.settlement.api.SettlementView;
import com.academy.paybridge.settlement.api.StatementLine;
import com.academy.paybridge.settlement.domain.ReconciliationBreak;
import com.academy.paybridge.settlement.domain.ReconciliationEngine;
import com.academy.paybridge.settlement.domain.ReconciliationEngine.Break;
import com.academy.paybridge.settlement.domain.ReconciliationEngine.Line;
import com.academy.paybridge.settlement.domain.ReconciliationRun;
import com.academy.paybridge.settlement.domain.ReconciliationStatus;
import com.academy.paybridge.settlement.domain.SettlementBatch;
import com.academy.paybridge.settlement.domain.SettlementItem;
import com.academy.paybridge.settlement.domain.SettlementPayment;
import com.academy.paybridge.settlement.repository.ReconciliationBreakRepository;
import com.academy.paybridge.settlement.repository.ReconciliationRunRepository;
import com.academy.paybridge.settlement.repository.SettlementBatchRepository;
import com.academy.paybridge.settlement.repository.SettlementItemRepository;
import com.academy.paybridge.settlement.repository.SettlementPaymentRepository;
import com.academy.paybridge.shared.exception.BusinessException;
import com.academy.paybridge.shared.money.Currency;
import com.academy.paybridge.shared.money.Money;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class SettlementService implements SettlementApi {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneOffset.UTC);

    private final SettlementBatchRepository batchRepository;
    private final SettlementItemRepository itemRepository;
    private final SettlementPaymentRepository paymentRepository;
    private final ReconciliationRunRepository runRepository;
    private final ReconciliationBreakRepository breakRepository;
    private final AccountApi accountApi;
    private final LedgerApi ledgerApi;
    private final Clock clock;
    private final Duration minAge;

    public SettlementService(SettlementBatchRepository batchRepository,
                             SettlementItemRepository itemRepository,
                             SettlementPaymentRepository paymentRepository,
                             ReconciliationRunRepository runRepository,
                             ReconciliationBreakRepository breakRepository,
                             AccountApi accountApi,
                             LedgerApi ledgerApi,
                             Clock clock,
                             @Value("${paybridge.settlement.min-age:PT0S}") Duration minAge) {
        this.batchRepository = batchRepository;
        this.itemRepository = itemRepository;
        this.paymentRepository = paymentRepository;
        this.runRepository = runRepository;
        this.breakRepository = breakRepository;
        this.accountApi = accountApi;
        this.ledgerApi = ledgerApi;
        this.clock = clock;
        this.minAge = minAge;
    }

    @Override
    @Transactional
    public SettlementView generate(UUID partnerId) {
        Instant now = clock.instant();

        // Locks the rows it returns: a concurrent request waits, then finds them already claimed.
        List<SettlementItem> items = itemRepository
                .findTop1000ByPartnerIdAndBatchIdIsNullAndCompletedAtBeforeOrderByCompletedAtAsc(
                        partnerId, now.minus(minAge));
        if (items.isEmpty()) {
            throw new BusinessException("NOTHING_TO_SETTLE",
                    "There are no completed remittances waiting to be settled");
        }

        long gross = 0;
        long fee = 0;
        long payout = 0;
        for (SettlementItem item : items) {
            gross = Math.addExact(gross, item.getSendAmountMinor());
            fee = Math.addExact(fee, item.getFeeMinor());
            payout = Math.addExact(payout, item.getReceiveAmountMinor());
        }

        SettlementBatch batch = batchRepository.save(
                SettlementBatch.generate(partnerId, newReference(now), items.size(), gross, fee, payout, now));
        for (SettlementItem item : items) {
            item.assignTo(batch.getId());
        }
        return toView(batch);
    }

    @Override
    @Transactional(readOnly = true)
    public List<SettlementView> list(UUID partnerId) {
        return batchRepository.findTop50ByPartnerIdOrderByCreatedAtDesc(partnerId).stream()
                .map(SettlementService::toView)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public SettlementDetail detail(UUID partnerId, UUID batchId) {
        SettlementBatch batch = batchRepository.findById(batchId)
                .filter(b -> b.getPartnerId().equals(partnerId))
                .orElseThrow(() -> notFound(batchId));
        List<SettlementItemView> items = itemRepository.findByBatchIdOrderByCompletedAtAsc(batchId).stream()
                .map(i -> new SettlementItemView(i.getRemittanceId(), i.getReference(),
                        usd(i.getSendAmountMinor()), usd(i.getFeeMinor()),
                        new Money(i.getReceiveAmountMinor(), Currency.NGN), i.getCompletedAt()))
                .toList();
        return new SettlementDetail(toView(batch), items);
    }

    @Override
    @Transactional
    public PaymentView recordPayment(RecordPaymentCommand command) {
        if (command.amount().currency() != Currency.USD) {
            throw new BusinessException("CURRENCY_MISMATCH", "Settlement payments are in USD");
        }

        Optional<SettlementPayment> existing = paymentRepository.findByIdempotencyKey(command.idempotencyKey());
        if (existing.isPresent()) {
            SettlementPayment payment = existing.get();
            boolean same = payment.getBatchId().equals(command.batchId())
                    && payment.getAmountUsdMinor() == command.amount().minorUnits()
                    && payment.getWireReference().equals(command.wireReference());
            if (!same) {
                throw new BusinessException("IDEMPOTENCY_CONFLICT",
                        "This Idempotency-Key was already used for a different payment");
            }
            SettlementBatch batch = batchRepository.findById(payment.getBatchId())
                    .orElseThrow(() -> notFound(payment.getBatchId()));
            return paymentView(payment, batch, true);
        }

        // Lock the batch first: payments to one batch are applied strictly one after another.
        SettlementBatch batch = lockOwned(command.batchId(), command.partnerId());
        Instant now = clock.instant();
        batch.recordPayment(command.amount().minorUnits(), now);   // open? positive? not more than outstanding?

        AccountView partnerAccount = accountApi.partnerAccount(command.partnerId(), Currency.USD);
        PostingResult posted = ledgerApi.post(new PostingRequest(
                "STL:PAY:" + command.idempotencyKey(),
                LedgerTransactionType.SETTLEMENT,
                "Settlement payment " + batch.getReference() + " wire " + command.wireReference(),
                List.of(PostingLine.debit(SystemAccounts.NOSTRO_USD, command.amount()),
                        PostingLine.credit(partnerAccount.id(), command.amount())),
                "STL:" + batch.getId()));

        SettlementPayment payment = paymentRepository.saveAndFlush(SettlementPayment.of(
                batch.getId(), command.partnerId(), command.idempotencyKey(), command.wireReference(),
                command.amount().minorUnits(), posted.transactionId(), now));
        return paymentView(payment, batch, false);
    }

    @Override
    @Transactional
    public ReconciliationReport reconcile(UUID partnerId, UUID batchId, List<StatementLine> statement) {
        SettlementBatch batch = lockOwned(batchId, partnerId);

        List<Line> ours = itemRepository.findByBatchIdOrderByCompletedAtAsc(batchId).stream()
                .map(i -> new Line(i.getReference(), i.getSendAmountMinor()))
                .toList();
        List<Line> theirs = statement.stream()
                .map(l -> new Line(l.reference().strip(), l.amount().minorUnits()))
                .toList();

        List<Break> breaks = ReconciliationEngine.compare(ours, theirs);
        ReconciliationStatus result = breaks.isEmpty() ? ReconciliationStatus.MATCHED : ReconciliationStatus.BREAKS;

        long statementTotal = 0;
        for (Line line : theirs) {
            statementTotal = Math.addExact(statementTotal, line.amountMinor());
        }

        Instant now = clock.instant();
        ReconciliationRun run = runRepository.save(ReconciliationRun.of(
                batchId, result, theirs.size(), statementTotal, batch.getGrossUsdMinor(), breaks.size(), now));
        for (Break b : breaks) {
            breakRepository.save(ReconciliationBreak.of(
                    run.getId(), b.kind().name(), b.reference(), b.ourAmountMinor(), b.theirAmountMinor()));
        }

        batch.applyReconciliation(result, now);   // closes the batch if it is also fully paid

        List<BreakView> breakViews = breaks.stream()
                .map(b -> new BreakView(b.kind().name(), b.reference(),
                        b.ourAmountMinor() == null ? null : usd(b.ourAmountMinor()),
                        b.theirAmountMinor() == null ? null : usd(b.theirAmountMinor())))
                .toList();
        return new ReconciliationReport(run.getId(), result.name(), theirs.size(),
                usd(statementTotal), usd(batch.getGrossUsdMinor()), breakViews, toView(batch));
    }

    // ---- helpers ----

    private SettlementBatch lockOwned(UUID batchId, UUID partnerId) {
        return batchRepository.findByIdForUpdate(batchId)
                .filter(b -> b.getPartnerId().equals(partnerId))
                .orElseThrow(() -> notFound(batchId));
    }

    private static BusinessException notFound(UUID batchId) {
        return new BusinessException("SETTLEMENT_NOT_FOUND", "No settlement with id " + batchId);
    }

    private static String newReference(Instant now) {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 6).toUpperCase();
        return "STL-" + DAY.format(now) + "-" + suffix;
    }

    private static Money usd(long minor) {
        return new Money(minor, Currency.USD);
    }

    private static PaymentView paymentView(SettlementPayment p, SettlementBatch batch, boolean replayed) {
        return new PaymentView(p.getId(), p.getWireReference(), usd(p.getAmountUsdMinor()),
                p.getCreatedAt(), toView(batch), replayed);
    }

    private static SettlementView toView(SettlementBatch b) {
        return new SettlementView(
                b.getId(), b.getReference(), b.getStatus().name(), b.getReconciliationStatus().name(),
                b.getItemCount(),
                usd(b.getGrossUsdMinor()),
                usd(b.getFeeUsdMinor()),
                usd(b.getGrossUsdMinor() - b.getFeeUsdMinor()),
                new Money(b.getPayoutNgnMinor(), Currency.NGN),
                usd(b.getPaidUsdMinor()),
                usd(b.outstandingMinor()),
                b.getCreatedAt(), b.getClosedAt());
    }
}