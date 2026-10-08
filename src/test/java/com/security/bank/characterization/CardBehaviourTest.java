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

/** /card/** behaviour. */
class CardBehaviourTest extends IntegrationTestBase {

    private JsonNode card(Session s, AccountRef a) {
	return get("/account/getAccount/summary?accountNumber=" + a.accountNumber(), s.token()).json().get("card");
    }

    private static Map<String, Object> newCard(String type, Long pin) {
	Map<String, Object> m = new HashMap<>();
	m.put("cardHolderName", "Holder Name");
	m.put("cardType", type);
	m.put("pin", pin);
	return m;
    }

    @Test
    void blockCardDeletesItAndDetachesFromAccount() {
	Session s = customer("alice");
	AccountRef a = openAccount(s, "SAVINGS", 100);
	HttpResult r = get("/card/block?accountNumber=" + a.accountNumber() + "&cardNumber=" + a.cardNumber(),
		s.token());
	assertThat(r.status()).isEqualTo(200);
	assertThat(r.body()).isEqualTo("Card Blocked Successfully");
	assertThat(r.header("Content-Type")).isEqualTo("text/plain;charset=UTF-8");
	assertThat(count("card")).isZero();
	assertThat(card(s, a).isNull()).isTrue();
    }

    @Test
    void blockingSomeoneElsesCardViaYourAccountIs500() {
	Session s = customer("alice");
	AccountRef a = openAccount(s, "SAVINGS", 100);
	AccountRef b = openAccount(s, "CURRENT", 100);
	assertThat(get("/card/block?accountNumber=" + a.accountNumber() + "&cardNumber=" + b.cardNumber(), s.token())
		.status()).isEqualTo(500);
	assertThat(count("card")).isEqualTo(2);
    }

    @Test
    void blockUnknownCardOrAccountIs500() {
	Session s = customer("alice");
	AccountRef a = openAccount(s, "SAVINGS", 100);
	assertThat(get("/card/block?accountNumber=" + a.accountNumber() + "&cardNumber=1", s.token()).status())
		.isEqualTo(500);
	assertThat(get("/card/block?accountNumber=1&cardNumber=" + a.cardNumber(), s.token()).status())
		.isEqualTo(500);
    }

    @Test
    void blockOnAccountWithoutCardIs500() {
	Session s = customer("alice");
	AccountRef savings = openAccount(s, "SAVINGS", 100);
	AccountRef ppf = openAccount(s, "PPF", 100);
	assertThat(get("/card/block?accountNumber=" + ppf.accountNumber() + "&cardNumber=" + savings.cardNumber(),
		s.token()).status()).isEqualTo(500);
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({ "DEBIT_CLASSIC,DEBIT_CLASSIC,20000", "CREDIT_PREMIUM,CREDIT_PREMIUM,50000",
	    "CREDIT_MASTER,CREDIT_MASTER,75000", "DEBIT_GLOBAL,DEBIT_GLOBAL,40000", "ANYTHING,DEBIT_GLOBAL,40000" })
    void applyNewCardOnAccountWithoutCard(String requested, String issued, double limit) {
	Session s = customer("alice");
	AccountRef a = openAccount(s, "PPF", 100);
	HttpResult r = post("/card/apply/new?accountNumber=" + a.accountNumber(), s.token(), newCard(requested, 4321L));
	assertThat(r.status()).isEqualTo(201);
	assertThat(r.body()).isEqualTo("New Card Allocated to account wih Number: " + a.accountNumber());
	JsonNode c = card(s, a);
	assertThat(c.get("cardType").asText()).isEqualTo(issued);
	assertThat(c.get("dailyLimit").asDouble()).isEqualTo(limit);
	assertThat(c.get("pin").asLong()).isEqualTo(4321L);
	assertThat(c.get("cardHolderName").asText()).isEqualTo("Holder Name");
	assertThat(c.get("status").asText()).isEqualTo("ACTIVE");
	assertThat(c.get("cvv").asInt()).isBetween(100, 999);
    }

    @Test
    void applyNewCardWhenAccountAlreadyHasOneIs500() {
	Session s = customer("alice");
	AccountRef a = openAccount(s, "SAVINGS", 100);
	assertThat(post("/card/apply/new?accountNumber=" + a.accountNumber(), s.token(), newCard("CREDIT_MASTER", 1L))
		.status()).isEqualTo(500);
	assertThat(count("card")).isEqualTo(1);
    }

    @Test
    void applyAfterBlockIssuesReplacement() {
	Session s = customer("alice");
	AccountRef a = openAccount(s, "SAVINGS", 100);
	get("/card/block?accountNumber=" + a.accountNumber() + "&cardNumber=" + a.cardNumber(), s.token());
	assertThat(post("/card/apply/new?accountNumber=" + a.accountNumber(), s.token(), newCard("CREDIT_MASTER", 9L))
		.status()).isEqualTo(201);
	assertThat(card(s, a).get("cardType").asText()).isEqualTo("CREDIT_MASTER");
	assertThat(count("card")).isEqualTo(1);
    }

    @Test
    void applyWithoutCardTypeIs500() {
	Session s = customer("alice");
	AccountRef a = openAccount(s, "PPF", 100);
	assertThat(post("/card/apply/new?accountNumber=" + a.accountNumber(), s.token(), newCard(null, 1L)).status())
		.isEqualTo(500);
	assertThat(count("card")).isZero();
    }

    @Test
    void applyOnUnknownAccountIs500() {
	Session s = customer("alice");
	assertThat(post("/card/apply/new?accountNumber=1", s.token(), newCard("DEBIT_CLASSIC", 1L)).status())
		.isEqualTo(500);
    }

    @ParameterizedTest(name = "{0} max {1}")
    @CsvSource({ "SAVINGS,50000", "CURRENT,75000", "SALARY,100000" })
    void limitChangesAreCappedPerCardType(String accountType, double max) {
	Session s = customer("alice");
	AccountRef a = openAccount(s, accountType, 100);
	double original = card(s, a).get("dailyLimit").asDouble();

	assertThat(put("/card/setting?cardNumber=" + a.cardNumber(), s.token(), Map.of("dailyLimit", max + 1))
		.status()).isEqualTo(200);
	assertThat(card(s, a).get("dailyLimit").asDouble()).isEqualTo(original);

	assertThat(put("/card/setting?cardNumber=" + a.cardNumber(), s.token(), Map.of("dailyLimit", max)).status())
		.isEqualTo(200);
	assertThat(card(s, a).get("dailyLimit").asDouble()).isEqualTo(max);

	put("/card/setting?cardNumber=" + a.cardNumber(), s.token(), Map.of("dailyLimit", 0));
	assertThat(card(s, a).get("dailyLimit").asDouble()).isEqualTo(max);
    }

    @Test
    void debitClassicLimitCapIs40000() {
	Session s = customer("alice");
	AccountRef a = openAccount(s, "PPF", 100);
	post("/card/apply/new?accountNumber=" + a.accountNumber(), s.token(), newCard("DEBIT_CLASSIC", 1L));
	long cardNumber = card(s, a).get("cardNumber").asLong();
	put("/card/setting?cardNumber=" + cardNumber, s.token(), Map.of("dailyLimit", 40001));
	assertThat(card(s, a).get("dailyLimit").asDouble()).isEqualTo(20000);
	put("/card/setting?cardNumber=" + cardNumber, s.token(), Map.of("dailyLimit", 40000));
	assertThat(card(s, a).get("dailyLimit").asDouble()).isEqualTo(40000);
    }

    @Test
    void negativeLimitIsAccepted() {
	Session s = customer("alice");
	AccountRef a = openAccount(s, "SAVINGS", 100);
	put("/card/setting?cardNumber=" + a.cardNumber(), s.token(), Map.of("dailyLimit", -1));
	assertThat(card(s, a).get("dailyLimit").asDouble()).isEqualTo(-1);
    }

    @Test
    void pinIsUpdatedOnlyWhenProvidedAndOtherFieldsAreIgnored() {
	Session s = customer("alice");
	AccountRef a = openAccount(s, "SAVINGS", 100);
	HttpResult r = put("/card/setting?cardNumber=" + a.cardNumber(), s.token(),
		Map.of("pin", 7777, "status", "BLOCKED", "cardType", "CREDIT_MASTER", "cvv", 1));
	assertThat(r.status()).isEqualTo(200);
	assertThat(r.body()).isEmpty();
	JsonNode c = card(s, a);
	assertThat(c.get("pin").asLong()).isEqualTo(7777L);
	assertThat(c.get("status").asText()).isEqualTo("ACTIVE");
	assertThat(c.get("cardType").asText()).isEqualTo("DEBIT_GLOBAL");
	assertThat(c.get("dailyLimit").asDouble()).isEqualTo(40000);

	put("/card/setting?cardNumber=" + a.cardNumber(), s.token(), Map.of("dailyLimit", 100));
	assertThat(card(s, a).get("pin").asLong()).isEqualTo(7777L);
    }

    @Test
    void settingOnUnknownCardIs500() {
	Session s = customer("alice");
	assertThat(put("/card/setting?cardNumber=1", s.token(), Map.of("dailyLimit", 1)).status()).isEqualTo(500);
    }
}
