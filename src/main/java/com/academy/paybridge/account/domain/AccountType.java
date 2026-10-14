package com.academy.paybridge.account.domain;

public enum AccountType {
    CUSTOMER,   // a person's account: never below zero
    SYSTEM,     // PayBridge's own books: may go negative
    PARTNER     // a partner's position account: may go negative, down to its credit limit
}