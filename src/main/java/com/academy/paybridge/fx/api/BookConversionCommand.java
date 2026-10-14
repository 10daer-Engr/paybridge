package com.academy.paybridge.fx.api;

import java.util.UUID;

public record BookConversionCommand(
        UUID quoteId,
        UUID requesterId,          // the partner who owns the quote
        UUID partnerAccountId,     // the partner's USD position account (debited)
        UUID destinationAccountId, // the NGN account that receives the naira (credited)
        String reference) {        // unique per exchange; also used as the correlation id
}