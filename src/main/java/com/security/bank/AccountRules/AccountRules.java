package com.security.bank.AccountRules;
import java.util.Calendar;
import java.util.Date;

import org.springframework.stereotype.Component;

import com.security.bank.entity.Account;
import com.security.bank.entity.AccountType;
import com.security.bank.entity.BranchType;
import com.security.bank.entity.Card;
import com.security.bank.entity.CardType;

@Component
public class AccountRules {

    public void applyDefaultsForAccount(Account acc, AccountType type) {
	switch (type) {
	case SAVINGS:
	    acc.setAccountType(AccountType.SAVINGS);
	    acc.setInterestRate(2.70f);
	    acc.setBranch(BranchType.BOB);
	    ensureCard(acc, CardType.DEBIT_GLOBAL, 40000);
	    break;
	case CURRENT:
	    acc.setAccountType(AccountType.CURRENT);
	    acc.setInterestRate(5.2f);
	    acc.setBranch(BranchType.ICIC);
	    ensureCard(acc, CardType.CREDIT_PREMIUM, 50000);
	    break;
	case PPF:
	    acc.setAccountType(AccountType.PPF);
	    acc.setInterestRate(7.4f);
	    acc.setBranch(BranchType.SBI);
	    break;
	case SALARY:
	    acc.setAccountType(AccountType.SALARY);
	    acc.setInterestRate(4.1f);
	    acc.setBranch(BranchType.HDFC);
	    ensureCard(acc, CardType.CREDIT_MASTER, 75000);
	    break;
	}
    }

    public void ensureCard(Account acc, CardType type, double limit) {
	if (acc.getCard() == null)
	    acc.setCard(new Card());
	Card c = acc.getCard();
	c.setCardType(type);
	c.setDailyLimit(limit);
	c.setCvv((int) (100 + Math.random() * 900));
	c.setCardNumber(System.currentTimeMillis());
	c.setAllocationDate(new Date());
	Calendar cal = Calendar.getInstance();
	cal.setTime(new Date());
	cal.add(Calendar.YEAR, 5);
	c.setExpiryDate(cal.getTime());
	c.setStatus("ACTIVE");
    }

    public double maxLimitFor(CardType type) {
	switch (type) {
	case DEBIT_CLASSIC:
	    return 40000;
	case DEBIT_GLOBAL:
	    return 50000;
	case CREDIT_PREMIUM:
	    return 75000;
	case CREDIT_MASTER:
	    return 100000;
	default:
	    return 40000;
	}
    }
}
