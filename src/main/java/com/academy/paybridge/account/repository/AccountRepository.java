package com.academy.paybridge.account.repository;

import com.academy.paybridge.account.domain.Account;
import com.academy.paybridge.account.domain.AccountType;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AccountRepository extends JpaRepository<Account, UUID>{
    Optional<Account> findByAccountNumber(String accountNumber);

    boolean existsByAccountNumber(String accountNumber);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from Account a where a.id = :id")
    Optional<Account> findByIdForUpdate(@Param("id") UUID id);



    List<Account> findByCustomerIdAndAccountTypeOrderByCreatedAtAsc(UUID customerId, AccountType accountType);

    long countByCustomerIdAndAccountType(UUID customerId, AccountType accountType);
}

