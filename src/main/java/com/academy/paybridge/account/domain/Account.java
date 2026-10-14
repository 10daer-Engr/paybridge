package com.academy.paybridge.account.domain;

import com.academy.paybridge.shared.exception.BusinessException;
import com.academy.paybridge.shared.money.Currency;
import com.academy.paybridge.shared.money.Money;
import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "account")
public class Account {

    @Id
    private UUID id;

    @Column(name = "account_number", nullable = false, updatable = false, length = 10)
    private String accountNumber;

    /** The owner: a customer id, or a partner id for PARTNER accounts. */
    @Column(name = "customer_id", nullable = false, updatable = false)
    private UUID customerId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 3)
    private Currency currency;

    @Column(name = "balance_minor", nullable = false)
    private long balanceMinor;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AccountStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "account_type", nullable = false, updatable = false, length = 20)
    private AccountType accountType;

    /** For PARTNER accounts: the lowest balance allowed (minus the credit limit). Null otherwise. */
    @Column(name = "balance_floor_minor")
    private Long balanceFloorMinor;

    @Version
    private Long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Account() {
        // required by JPA
    }

    public static Account open(UUID customerId, String accountNumber, Currency currency, Instant now) {
        Account account = new Account();
        account.id = UUID.randomUUID();
        account.customerId = customerId;
        account.accountNumber = accountNumber;
        account.currency = currency;
        account.balanceMinor = 0;
        account.status = AccountStatus.ACTIVE;
        account.accountType = AccountType.CUSTOMER;
        account.createdAt = now;
        account.updatedAt = now;
        return account;
    }

    /** A partner's position account. A negative balance means the partner owes PayBridge. */
    public static Account openPartner(UUID partnerId, String accountNumber, Money creditLimit, Instant now) {
        Account account = new Account();
        account.id = UUID.randomUUID();
        account.customerId = partnerId;
        account.accountNumber = accountNumber;
        account.currency = creditLimit.currency();
        account.balanceMinor = 0;
        account.status = AccountStatus.ACTIVE;
        account.accountType = AccountType.PARTNER;
        account.balanceFloorMinor = -creditLimit.minorUnits();
        account.createdAt = now;
        account.updatedAt = now;
        return account;
    }

    public void credit(Money amount, Instant now) {
        requireValidAmount(amount);
        if (status == AccountStatus.CLOSED) {
            throw new BusinessException("ACCOUNT_CLOSED", "Account " + accountNumber + " is closed");
        }
        balanceMinor = Math.addExact(balanceMinor, amount.minorUnits());
        updatedAt = now;
    }

    public void debit(Money amount, Instant now) {
        requireValidAmount(amount);
        if (status != AccountStatus.ACTIVE) {
            throw new BusinessException("ACCOUNT_NOT_ACTIVE",
                    "Account " + accountNumber + " cannot be debited while " + status);
        }
        if (accountType == AccountType.CUSTOMER && balanceMinor < amount.minorUnits()) {
            throw new BusinessException("INSUFFICIENT_FUNDS",
                    "Account " + accountNumber + " has insufficient funds");
        }
        if (accountType == AccountType.PARTNER) {
            long resulting = Math.subtractExact(balanceMinor, amount.minorUnits());
            if (balanceFloorMinor == null || resulting < balanceFloorMinor) {
                throw new BusinessException("CREDIT_LIMIT_EXCEEDED",
                        "This would exceed the partner's credit limit. Settle the outstanding balance first.");
            }
        }
        balanceMinor = Math.subtractExact(balanceMinor, amount.minorUnits());
        updatedAt = now;
    }

    private void requireValidAmount(Money amount) {
        if (amount.currency() != currency) {
            throw new BusinessException("CURRENCY_MISMATCH",
                    "Account is " + currency + " but amount is " + amount.currency());
        }
        if (!amount.isPositive()) {
            throw new BusinessException("INVALID_AMOUNT", "Amount must be greater than zero");
        }
    }

    public UUID getId() { return id; }
    public String getAccountNumber() { return accountNumber; }
    public UUID getCustomerId() { return customerId; }
    public Currency getCurrency() { return currency; }
    public Money getBalance() { return new Money(balanceMinor, currency); }
    public AccountStatus getStatus() { return status; }
    public AccountType getAccountType() { return accountType; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}