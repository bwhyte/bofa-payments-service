package com.security.bank.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import com.security.bank.entity.Account;
import com.security.bank.entity.AccountType;
import com.security.bank.entity.BranchType;

@Repository
public interface AccountRepository extends JpaRepository<Account, Long> {
    Optional<Account> findByAccountNumber(Long accountNumber);

    @Query("SELECT a FROM Account a WHERE a.status = 'ACTIVE'")
    List<Account> findAllActiveAccounts();

    @Query("SELECT a FROM Account a WHERE a.status = 'INACTIVE'")
    List<Account> findAllInActiveAccounts();

    @Query("SELECT a FROM Account a WHERE a.accountType = :accountType")
    List<Account> findAllByAccountType(AccountType accountType);

    @Query("SELECT a FROM Account a WHERE a.branch = :branchType")
    List<Account> findAllByBranch(BranchType branchType);

}