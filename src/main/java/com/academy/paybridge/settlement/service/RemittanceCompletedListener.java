package com.academy.paybridge.settlement.service;

import com.academy.paybridge.remittance.api.RemittanceCompletedEvent;
import com.academy.paybridge.settlement.domain.SettlementItem;
import com.academy.paybridge.settlement.repository.SettlementItemRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
class RemittanceCompletedListener {

    private final SettlementItemRepository itemRepository;

    RemittanceCompletedListener(SettlementItemRepository itemRepository) {
        this.itemRepository = itemRepository;
    }

    /**
     * BEFORE_COMMIT: runs inside the remittance's own transaction. The settlement item and the completed
     * remittance commit together or roll back together, so a completed remittance can never go unsettled.
     * (This kind of listener does nothing at all if the event is published outside a transaction.)
     */
    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    void onCompleted(RemittanceCompletedEvent event) {
        String reference = event.partnerReference() != null
                ? event.partnerReference()
                : event.remittanceId().toString();

        itemRepository.save(SettlementItem.pending(
                event.partnerId(), event.remittanceId(), reference,
                event.sendAmount().minorUnits(), event.fee().minorUnits(), event.receiveAmount().minorUnits(),
                event.completedAt()));
    }
}