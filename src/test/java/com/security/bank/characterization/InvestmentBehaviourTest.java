package com.security.bank.characterization;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.fasterxml.jackson.databind.JsonNode;
import com.security.bank.support.HttpResult;
import com.security.bank.support.IntegrationTestBase;

/** /invest/now behaviour. */
class InvestmentBehaviourTest extends IntegrationTestBase {

    private static Map<String, Object> invest(String type, double amount) {
	Map<String, Object> m = new HashMap<>();
	m.put("investmentType", type);
	m.put("amount", amount);
	m.put("duration", "12 months");
	return m;
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({ "GOLD,GOLD,Low,12.0,BuyNow", "STOCKS,STOCKS,high,20.0,StockWay",
	    "MUTUAL_FUND,MUTUAL_FUND,Moderate,12.3,ray fund", "FIXED_DEPOSITS,FIXED_DEPOSITS,Low,9.2,PST",
	    "ANYTHING,FIXED_DEPOSITS,Low,9.2,PST" })
    void investmentTypesGetTheirDefaults(String requested, String type, String risk, double returns,
	    String company) {
	Session s = customer("alice");
	AccountRef a = openAccount(s, "SAVINGS", 1000);
	HttpResult r = post("/invest/now?accountId=" + a.id(), s.token(), invest(requested, 250));
	assertThat(r.status()).isEqualTo(200);
	assertThat(r.body()).isEqualTo("Investment successful");

	Map<String, Object> row = jdbc.queryForMap("SELECT * FROM investment");
	assertThat(row.get("INVESTMENT_TYPE")).isEqualTo(type);
	assertThat(row.get("RISK")).isEqualTo(risk);
	assertThat(((Number) row.get("RETURNS")).doubleValue()).isCloseTo(returns, org.assertj.core.data.Offset.offset(1e-5));
	assertThat(row.get("COMPANY_NAME")).isEqualTo(company);
	assertThat(((Number) row.get("AMOUNT")).doubleValue()).isEqualTo(250.0);
	assertThat(row.get("DURATION")).isEqualTo("12 months");
	assertThat(((Number) row.get("USER_ID")).longValue()).isEqualTo(s.userId());
    }

    @Test
    void investingDoesNotDebitTheAccount() {
	Session s = customer("alice");
	AccountRef a = openAccount(s, "SAVINGS", 1000);
	post("/invest/now?accountId=" + a.id(), s.token(), invest("GOLD", 999));
	post("/invest/now?accountId=" + a.id(), s.token(), invest("GOLD", 999));
	assertThat(count("investment")).isEqualTo(2);
	assertThat(get("/account/balance?accountNumber=" + a.accountNumber(), s.token()).body()).isEqualTo("1000.0");
    }

    @Test
    void amountMustBeStrictlyLessThanBalance() {
	Session s = customer("alice");
	AccountRef a = openAccount(s, "SAVINGS", 1000);
	assertThat(post("/invest/now?accountId=" + a.id(), s.token(), invest("GOLD", 1000)).status()).isEqualTo(500);
	assertThat(post("/invest/now?accountId=" + a.id(), s.token(), invest("GOLD", 5000)).status()).isEqualTo(500);
	assertThat(count("investment")).isZero();
    }

    @Test
    void negativeAmountIsAccepted() {
	Session s = customer("alice");
	AccountRef a = openAccount(s, "SAVINGS", 10);
	assertThat(post("/invest/now?accountId=" + a.id(), s.token(), invest("GOLD", -100)).status()).isEqualTo(200);
    }

    @Test
    void missingInvestmentTypeIs500() {
	Session s = customer("alice");
	AccountRef a = openAccount(s, "SAVINGS", 1000);
	assertThat(post("/invest/now?accountId=" + a.id(), s.token(), invest(null, 1)).status()).isEqualTo(500);
    }

    @Test
    void unknownAccountIs500() {
	Session s = customer("alice");
	assertThat(post("/invest/now?accountId=999", s.token(), invest("GOLD", 1)).status()).isEqualTo(500);
    }

    @Test
    void investmentsAppearOnTheUserForAdmins() {
	Session s = customer("alice");
	AccountRef a = openAccount(s, "SAVINGS", 1000);
	post("/invest/now?accountId=" + a.id(), s.token(), invest("STOCKS", 10));
	Session admin = admin("root");
	JsonNode inv = get("/admin/getUserByName/alice", admin.token()).json().get("investmentList");
	assertThat(inv.size()).isEqualTo(1);
	assertThat(AccountBehaviourTest.fieldNames(inv.get(0))).containsExactly("id", "investmentType", "risk",
		"amount", "returns", "duration", "companyName");
	assertThat(inv.get(0).get("investmentType").asText()).isEqualTo("STOCKS");
	assertThat(inv.get(0).get("returns").asDouble()).isEqualTo(20.0);
    }
}
