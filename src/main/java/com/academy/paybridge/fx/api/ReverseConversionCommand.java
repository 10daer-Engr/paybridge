package com.academy.paybridge.fx.api;

import java.util.UUID;

public record ReverseConversionCommand(
        UUID quoteId,
        UUID requesterId,
        UUID partnerAccountId,
        UUID destinationAccountId,   // the account the NGN leg originally credited
        String reference) {          // the same reference used when booking
}