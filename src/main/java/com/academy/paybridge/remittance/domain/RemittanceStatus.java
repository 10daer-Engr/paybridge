package com.academy.paybridge.remittance.domain;

public enum RemittanceStatus {
    PENDING,      // recorded, nothing has moved yet
    PROCESSING,   // exchange booked, payout to a bank still in flight (Step 18)
    COMPLETED,    // the recipient has the money
    FAILED,       // refused before any money moved
    REVERSED;     // the exchange was booked, then undone (Step 18)

    public boolean canMoveTo(RemittanceStatus next) {
        return switch (this) {
            case PENDING -> next == PROCESSING || next == COMPLETED || next == FAILED;
            case PROCESSING -> next == COMPLETED || next == REVERSED;
            case COMPLETED, FAILED, REVERSED -> false;
        };
    }
}