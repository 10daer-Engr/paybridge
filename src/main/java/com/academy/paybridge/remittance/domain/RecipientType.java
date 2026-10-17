package com.academy.paybridge.remittance.domain;

public enum RecipientType {
    ACCOUNT,   // a PayBridge customer account
    BANK       // any Nigerian bank account, paid out through Paystack (Step 18)
}