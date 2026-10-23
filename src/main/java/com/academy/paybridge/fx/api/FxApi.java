package com.academy.paybridge.fx.api;

import com.academy.paybridge.shared.money.Currency;
import com.academy.paybridge.shared.money.Money;

import java.util.UUID;

public interface FxApi {

    CurrentRate currentRate(Currency from, Currency to);

    QuoteView createQuote(UUID requesterId, Money sendAmount, Currency receiveCurrency);

    /** Only the requester can see its quotes. Others get QUOTE_NOT_FOUND. */
    QuoteView getQuote(UUID quoteId, UUID requesterId);

    /**
     * Redeems a quote: it must belong to the requester, be unused and unexpired. Locks the quote row,
     * so it MUST run inside the same transaction that books the money.
     */
    QuoteView consumeQuote(UUID quoteId, UUID requesterId);

    /**
     * Redeems the quote and books both sides of the exchange: the USD posting (partner debited,
     * position and fee revenue credited) and the NGN posting (position debited, destination and
     * margin credited). All or nothing. MUST run inside the caller's transaction.
     */
    ConversionBooking bookConversion(BookConversionCommand command);

    /**
     * Undoes a booked exchange by posting the mirror image of its entries, using the amounts stored on the
     * quote (never today's rate). MUST run inside the caller's transaction.
     */
    ConversionReversal reverseConversion(ReverseConversionCommand command);
}