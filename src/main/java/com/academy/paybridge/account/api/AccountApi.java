package com.academy.paybridge.account.api;

import com.academy.paybridge.shared.money.Currency;
import com.academy.paybridge.shared.money.Money;

import java.util.List;
import java.util.UUID;

public interface AccountApi {

    AccountView openAccount(UUID customerId, Currency currency);

    AccountView getByAccountNumber(String accountNumber);

    /**
     * Returns the account only if it belongs to this customer. Otherwise it behaves exactly as if
     * the account did not exist, so callers cannot probe which account numbers are real.
     */
    AccountView requireOwned(String accountNumber, UUID customerId);

    List<AccountView> listForCustomer(UUID customerId);

    /** Opens a partner's position account. Its balance may go negative down to minus the credit limit. */
    AccountView openPartnerAccount(UUID partnerId, Money creditLimit);

    AccountView partnerAccount(UUID partnerId, Currency currency);

    /**
     * Increases the account balance under a row lock and returns the new balance.
     * Must be called inside an existing transaction.
     */
    Money credit(UUID accountId, Money amount);

    /**
     * Decreases the account balance under a row lock and returns the new balance.
     * Must be called inside an existing transaction.
     */
    Money debit(UUID accountId, Money amount);
}