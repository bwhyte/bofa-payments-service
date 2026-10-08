package com.security.bank.unit;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.ZoneId;
import java.util.Date;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.security.bank.AccountRules.AccountRules;
import com.security.bank.entity.Account;
import com.security.bank.entity.AccountType;
import com.security.bank.entity.Card;
import com.security.bank.entity.CardType;

class AccountRulesTest {

    private final AccountRules rules = new AccountRules();

    @ParameterizedTest(name = "{0}")
    @CsvSource({ "SAVINGS,2.7,BOB,DEBIT_GLOBAL,40000", "CURRENT,5.2,ICIC,CREDIT_PREMIUM,50000",
	    "SALARY,4.1,HDFC,CREDIT_MASTER,75000" })
    void defaultsForCardAccounts(AccountType type, float rate, String branch, CardType cardType, double limit) {
	Account acc = new Account();
	rules.applyDefaultsForAccount(acc, type);
	assertThat(acc.getAccountType()).isEqualTo(type);
	assertThat(acc.getInterestRate()).isEqualTo(rate);
	assertThat(acc.getBranch().name()).isEqualTo(branch);
	assertThat(acc.getCard().getCardType()).isEqualTo(cardType);
	assertThat(acc.getCard().getDailyLimit()).isEqualTo(limit);
	assertThat(acc.getCard().getStatus()).isEqualTo("ACTIVE");
    }

    @Test
    void ppfHasNoCard() {
	Account acc = new Account();
	rules.applyDefaultsForAccount(acc, AccountType.PPF);
	assertThat(acc.getInterestRate()).isEqualTo(7.4f);
	assertThat(acc.getBranch().name()).isEqualTo("SBI");
	assertThat(acc.getCard()).isNull();
    }

    @Test
    void ensureCardReusesExistingCardAndSetsFiveYearExpiry() {
	Account acc = new Account();
	Card existing = new Card();
	existing.setPin(42L);
	acc.setCard(existing);
	rules.ensureCard(acc, CardType.DEBIT_CLASSIC, 123);
	assertThat(acc.getCard()).isSameAs(existing);
	assertThat(existing.getPin()).isEqualTo(42L);
	assertThat(existing.getCvv()).isBetween(100, 999);
	assertThat(existing.getCardNumber()).isPositive();
	assertThat(existing.getDailyLimit()).isEqualTo(123);
	assertThat(toLocal(existing.getExpiryDate())).isEqualTo(toLocal(existing.getAllocationDate()).plusYears(5));
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({ "DEBIT_CLASSIC,40000", "DEBIT_GLOBAL,50000", "CREDIT_PREMIUM,75000", "CREDIT_MASTER,100000" })
    void maxLimits(CardType type, double max) {
	assertThat(rules.maxLimitFor(type)).isEqualTo(max);
    }

    private static java.time.LocalDate toLocal(Date d) {
	return d.toInstant().atZone(ZoneId.systemDefault()).toLocalDate();
    }
}
