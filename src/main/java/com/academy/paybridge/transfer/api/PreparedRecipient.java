package com.academy.paybridge.transfer.api;

/** A bank account that has been verified (name enquiry) and registered with the provider. */
public record PreparedRecipient(String recipientCode, String accountName) {
}