package com.academy.paybridge.settlement.domain;

public enum ReconciliationStatus {
    PENDING,   // no statement checked yet
    MATCHED,   // the latest statement agrees with our records exactly
    BREAKS     // the latest statement disagrees somewhere
}