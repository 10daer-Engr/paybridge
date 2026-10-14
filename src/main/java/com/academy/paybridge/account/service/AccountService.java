package com.academy.paybridge.account.service;

import com.academy.paybridge.account.api.AccountApi;
import com.academy.paybridge.account.api.AccountView;
import com.academy.paybridge.account.domain.Account;
import com.academy.paybridge.account.domain.AccountType;
import com.academy.paybridge.account.repository.AccountRepository;
import com.academy.paybridge.shared.exception.BusinessException;
import com.academy.paybridge.shared.money.Currency;
import com.academy.paybridge.shared.money.Money;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.List;
import java.util.UUID;

@Service
 class AccountService implements AccountApi {
    private static final int MAX_NUMBER_ATTEMPTS = 5;
    private static final int MAX_ACCOUNTS_PER_CUSTOMER = 10;

    private final AccountRepository accountRepository;
    private final AccountNumberGenerator numberGenerator;
    private final Clock clock;
    private final EntityManager entityManager;

    public AccountService(AccountRepository accountRepository,
                          AccountNumberGenerator numberGenerator,
                          Clock clock,
                          EntityManager entityManager){
        this.accountRepository = accountRepository;
        this.numberGenerator = numberGenerator;
        this.clock = clock;
        this.entityManager = entityManager;
    }

    @Override
    @Transactional
    public AccountView openAccount(UUID customerId, Currency currency){
        if (accountRepository.countByCustomerIdAndAccountType(customerId, AccountType.CUSTOMER)
                >= MAX_ACCOUNTS_PER_CUSTOMER) {
            throw new BusinessException("ACCOUNT_LIMIT_REACHED",
                    "A customer may hold at most " + MAX_ACCOUNTS_PER_CUSTOMER + " accounts");
        }
        String accountNumber = generateUniqueAccountNumber();
        Account account = Account.open(customerId, accountNumber, currency, clock.instant());
        return toView(accountRepository.save(account));

    }

    @Override
    @Transactional(readOnly = true)
    public AccountView getByAccountNumber(String accountNumber) {
        Account account = accountRepository.findByAccountNumber(accountNumber)
                .orElseThrow(() -> new BusinessException(
                        "ACCOUNT_NOT_FOUND", "No account with number " + accountNumber));
        AccountView view = toView(account);   // build the view first
        entityManager.detach(account);        // then drop it from the persistence context
        return view;
    }

    @Override
    @Transactional
    public AccountView openPartnerAccount(UUID partnerId, Money creditLimit) {
        String accountNumber = generateUniqueAccountNumber();
        Account account = Account.openPartner(partnerId, accountNumber, creditLimit, clock.instant());
        return toView(accountRepository.save(account));
    }

    @Override
    @Transactional(readOnly = true)
    public AccountView partnerAccount(UUID partnerId, Currency currency) {
        return accountRepository
                .findFirstByCustomerIdAndAccountTypeAndCurrency(partnerId, AccountType.PARTNER, currency)
                .map(AccountService::toView)
                .orElseThrow(() -> new BusinessException("PARTNER_ACCOUNT_NOT_FOUND",
                        "This partner has no " + currency + " position account. "
                                + "Partners registered before accounts existed must register again."));
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Money credit(UUID accountId, Money amount) {
        Account account = lockAccount(accountId);
        account.credit(amount, clock.instant());
        return account.getBalance();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Money debit(UUID accountId, Money amount) {
        Account account = lockAccount(accountId);
        account.debit(amount, clock.instant());
        return account.getBalance();
    }

    private Account lockAccount(UUID accountId) {
        return accountRepository.findByIdForUpdate(accountId)
                .orElseThrow(() -> new BusinessException(
                        "ACCOUNT_NOT_FOUND", "No account with id " + accountId));
    }

    private String generateUniqueAccountNumber(){
        for(int attempts = 0; attempts < MAX_NUMBER_ATTEMPTS; attempts++){
            String candidate = numberGenerator.next();
            if(!accountRepository.existsByAccountNumber(candidate)){
                return candidate;
            }
        }

        throw new IllegalStateException("could not generate a unique account number");
    }

    @Override
    @Transactional(readOnly = true)
    public AccountView requireOwned(String accountNumber, UUID customerId) {
        return accountRepository.findByAccountNumber(accountNumber)
                .filter(a -> a.getAccountType() == AccountType.CUSTOMER
                        && a.getCustomerId().equals(customerId))
                .map(AccountService::toView)
                // Same answer whether it is missing or simply not yours.
                .orElseThrow(() -> new BusinessException(
                        "ACCOUNT_NOT_FOUND", "No account with number " + accountNumber));
    }

    @Override
    @Transactional(readOnly = true)
    public List<AccountView> listForCustomer(UUID customerId) {
        return accountRepository
                .findByCustomerIdAndAccountTypeOrderByCreatedAtAsc(customerId, AccountType.CUSTOMER)
                .stream()
                .map(AccountService::toView)
                .toList();
    }

    private static AccountView toView(Account account){
        return new AccountView(
                account.getId(),
                account.getAccountNumber(),
                account.getCustomerId(),
                account.getCurrency(),
                account.getBalance(),
                account.getStatus().name(),
                account.getAccountType().name()

        );
    }
}
