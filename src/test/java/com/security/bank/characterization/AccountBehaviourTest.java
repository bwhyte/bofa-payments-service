package com.security.bank.characterization;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.fasterxml.jackson.databind.JsonNode;
import com.security.bank.support.HttpResult;
import com.security.bank.support.IntegrationTestBase;

/** /account/** behaviour for the account owner. */
class AccountBehaviourTest extends IntegrationTestBase {

    static List<String> fieldNames(JsonNode n) {
	List<String> names = new ArrayList<>();
	n.fieldNames().forEachRemaining(names::add);
	return names;
    }

    static Instant instant(JsonNode n) {
	return ZonedDateTime.parse(n.asText().replaceAll("([+-]\\d{2}):?(\\d{2})$", "$1:$2")).toInstant();
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({ "SAVINGS,2.7,BOB,DEBIT_GLOBAL,40000", "CURRENT,5.2,ICIC,CREDIT_PREMIUM,50000",
	    "SALARY,4.1,HDFC,CREDIT_MASTER,75000" })
    void accountTypesWithCardsGetTheirDefaults(String type, double rate, String branch, String cardType,
	    double limit) {
	Session s = customer("alice");
	HttpResult created = post("/account/create/" + s.userId(), s.token(), accountBody(type, 1500));
	assertThat(created.status()).isEqualTo(201);
	assertThat(created.body()).isEmpty();

	JsonNode list = get("/account/all/" + s.userId(), s.token()).json();
	assertThat(list.size()).isEqualTo(1);
	JsonNode acc = list.get(0);
	assertThat(fieldNames(acc)).containsExactly("id", "accountType", "status", "balance", "interestRate",
		"branch", "proof", "openingDate", "accountNumber", "nominee", "card", "user");
	assertThat(acc.get("user").get("username").asText()).isEqualTo("alice");
	assertThat(acc.get("user").has("accountList")).isFalse();
	assertThat(acc.get("user").get("password").asText()).startsWith("$2a$10$");
	assertThat(acc.get("accountType").asText()).isEqualTo(type);
	assertThat(acc.get("status").asText()).isEqualTo("ACTIVE");
	assertThat(acc.get("balance").asDouble()).isEqualTo(1500.0);
	assertThat(acc.get("interestRate").asDouble()).isEqualTo(rate);
	assertThat(acc.get("branch").asText()).isEqualTo(branch);
	assertThat(acc.get("proof").asText()).isEqualTo("PAN-123");
	assertThat(acc.get("accountNumber").asLong()).isBetween(10_000_000L, 99_999_999L);

	JsonNode card = acc.get("card");
	assertThat(fieldNames(card)).containsExactly("id", "cardNumber", "cardHolderName", "cardType", "dailyLimit",
		"cvv", "allocationDate", "expiryDate", "pin", "status");
	assertThat(card.get("cardType").asText()).isEqualTo(cardType);
	assertThat(card.get("dailyLimit").asDouble()).isEqualTo(limit);
	assertThat(card.get("pin").asLong()).isEqualTo(1122L);
	assertThat(card.get("cardHolderName").asText()).isEqualTo("Name alice");
	assertThat(card.get("status").asText()).isEqualTo("ACTIVE");
	assertThat(card.get("cvv").asInt()).isBetween(100, 999);
	assertThat(card.get("cardNumber").asLong()).isBetween(0L, 9_999_999_999_999_999L);
	ZonedDateTime alloc = instant(card.get("allocationDate")).atZone(ZoneId.systemDefault());
	ZonedDateTime expiry = instant(card.get("expiryDate")).atZone(ZoneId.systemDefault());
	assertThat(expiry.toLocalDate()).isEqualTo(alloc.toLocalDate().plusYears(5));
	assertThat(instant(acc.get("openingDate"))).isBetween(Instant.now().minusSeconds(60), Instant.now());

	JsonNode nominee = acc.get("nominee");
	assertThat(fieldNames(nominee)).containsExactly("id", "relation", "name", "accountNumber", "gender", "age");
	assertThat(nominee.get("name").asText()).isEqualTo("Jane Nominee");
	assertThat(nominee.get("relation").asText()).isEqualTo("Spouse");
	assertThat(nominee.get("accountNumber").asLong()).isEqualTo(99887766L);
	assertThat(nominee.get("gender").asText()).isEqualTo("F");
	assertThat(nominee.get("age").asInt()).isEqualTo(34);
    }

    @Test
    void ppfAccountHasNoCard() {
	Session s = customer("alice");
	openAccount(s, "PPF", 800);
	JsonNode acc = get("/account/all/" + s.userId(), s.token()).json().get(0);
	assertThat(acc.get("accountType").asText()).isEqualTo("PPF");
	assertThat(acc.get("interestRate").asDouble()).isEqualTo(7.4);
	assertThat(acc.get("branch").asText()).isEqualTo("SBI");
	assertThat(acc.get("card").isNull()).isTrue();
	assertThat(count("card")).isZero();
    }

    @Test
    void unknownAccountTypeIs500AndSavesNothing() {
	Session s = customer("alice");
	assertThat(post("/account/create/" + s.userId(), s.token(), accountBody("GOLD", 1)).status()).isEqualTo(500);
	assertThat(post("/account/create/" + s.userId(), s.token(), accountBody("savings", 1)).status())
		.isEqualTo(500);
	assertThat(count("account")).isZero();
	assertThat(count("card")).isZero();
	assertThat(count("nominee")).isZero();
    }

    @Test
    void missingAccountTypeIs500() {
	Session s = customer("alice");
	assertThat(post("/account/create/" + s.userId(), s.token(), Map.of("balance", 1)).status()).isEqualTo(500);
    }

    @Test
    void accountForUnknownUserIs500ButLeavesCardAndNomineeRows() {
	Session s = customer("alice");
	assertThat(post("/account/create/999", s.token(), accountBody("SAVINGS", 1)).status()).isEqualTo(500);
	assertThat(count("account")).isZero();
	assertThat(count("card")).isEqualTo(1);
	assertThat(count("nominee")).isEqualTo(1);
    }

    @Test
    void accountWithoutNomineeIs500() {
	Session s = customer("alice");
	Map<String, Object> body = new java.util.HashMap<>(accountBody("SAVINGS", 1));
	body.remove("nominee");
	assertThat(post("/account/create/" + s.userId(), s.token(), body).status()).isEqualTo(500);
	assertThat(count("account")).isZero();
    }

    @Test
    void negativeOpeningBalanceIsAccepted() {
	Session s = customer("alice");
	AccountRef a = openAccount(s, "SAVINGS", -50);
	assertThat(get("/account/balance?accountNumber=" + a.accountNumber(), s.token()).body()).isEqualTo("-50.0");
    }

    @Test
    void multipleAccountsAreListedInCreationOrder() {
	Session s = customer("alice");
	openAccount(s, "SAVINGS", 1);
	openAccount(s, "PPF", 2);
	openAccount(s, "SAVINGS", 3);
	JsonNode list = get("/account/all/" + s.userId(), s.token()).json();
	assertThat(list.size()).isEqualTo(3);
	assertThat(list.get(0).get("balance").asDouble()).isEqualTo(1.0);
	assertThat(list.get(1).get("accountType").asText()).isEqualTo("PPF");
	assertThat(list.get(2).get("balance").asDouble()).isEqualTo(3.0);
    }

    @Test
    void allAccountsForUnknownUserIs500() {
	Session s = customer("alice");
	assertThat(get("/account/all/999", s.token()).status()).isEqualTo(500);
    }

    @Test
    void balanceAndNomineeByAccountNumber() {
	Session s = customer("alice");
	AccountRef a = openAccount(s, "CURRENT", 2500.75);
	assertThat(get("/account/balance?accountNumber=" + a.accountNumber(), s.token()).body()).isEqualTo("2500.75");
	JsonNode nominee = get("/account/nominee?accountNumber=" + a.accountNumber(), s.token()).json();
	assertThat(fieldNames(nominee)).containsExactly("id", "relation", "name", "accountNumber", "gender", "age");
	assertThat(nominee.get("name").asText()).isEqualTo("Jane Nominee");
	assertThat(get("/account/nominee?accountNumber=1", s.token()).status()).isEqualTo(500);
    }

    @Test
    void updateNomineeOverwritesEveryFieldInPlace() {
	Session s = customer("alice");
	AccountRef a = openAccount(s, "SAVINGS", 100);
	long nomineeId = jdbc.queryForObject("SELECT nominee_id FROM account WHERE id = ?", Long.class, a.id());

	HttpResult r = put("/account/updateNominee/" + a.id(), s.token(),
		Map.of("name", "Bob", "relation", "Brother", "accountNumber", 11, "gender", "M", "age", 40));
	assertThat(r.status()).isEqualTo(200);
	assertThat(r.body()).isEmpty();
	JsonNode n = get("/account/nominee?accountNumber=" + a.accountNumber(), s.token()).json();
	assertThat(n.get("id").asLong()).isEqualTo(nomineeId);
	assertThat(n.get("name").asText()).isEqualTo("Bob");
	assertThat(n.get("relation").asText()).isEqualTo("Brother");
	assertThat(n.get("accountNumber").asLong()).isEqualTo(11);
	assertThat(n.get("gender").asText()).isEqualTo("M");
	assertThat(n.get("age").asInt()).isEqualTo(40);

	assertThat(put("/account/updateNominee/" + a.id(), s.token(), Map.of()).status()).isEqualTo(200);
	n = get("/account/nominee?accountNumber=" + a.accountNumber(), s.token()).json();
	assertThat(n.get("name").isNull()).isTrue();
	assertThat(n.get("age").asInt()).isZero();
	assertThat(count("nominee")).isEqualTo(1);
    }

    @Test
    void updateNomineeOnUnknownAccountIs500() {
	Session s = customer("alice");
	assertThat(put("/account/updateNominee/999", s.token(), nominee()).status()).isEqualTo(500);
    }

    @Test
    void kycDetailsReturnTheOwningUserIncludingPasswordHash() {
	Session s = customer("alice");
	AccountRef a = openAccount(s, "SAVINGS", 100);
	HttpResult r = get("/account/getKycDetails?accountNumber=" + a.accountNumber(), s.token());
	assertThat(r.status()).isEqualTo(200);
	JsonNode u = r.json();
	assertThat(fieldNames(u)).containsExactlyInAnyOrder("id", "name", "username", "password", "address", "number",
		"identityProof", "roles", "accountList", "investmentList", "authorities", "enabled",
		"accountNonExpired", "accountNonLocked", "credentialsNonExpired");
	assertThat(u.get("username").asText()).isEqualTo("alice");
	assertThat(u.get("password").asText()).startsWith("$2a$10$");
	assertThat(u.get("accountList").isNull()).isTrue();
	assertThat(u.get("investmentList").isNull()).isTrue();
	assertThat(u.get("roles").get("roleName").asText()).isEqualTo("ROLE_CUSTOMER");
	assertThat(u.get("authorities").get(0).get("authority").asText()).isEqualTo("ROLE_CUSTOMER");
	assertThat(u.get("enabled").asBoolean()).isTrue();
    }

    @Test
    void kycReadDoesNotDetachAccountsFromUser() {
	Session s = customer("alice");
	AccountRef a = openAccount(s, "SAVINGS", 100);
	get("/account/getKycDetails?accountNumber=" + a.accountNumber(), s.token());
	assertThat(get("/account/all/" + s.userId(), s.token()).json().size()).isEqualTo(1);
	assertThat(count("account")).isEqualTo(1);
    }

    @Test
    void updateKycSkipsEmptyStringsAndNullNumber() {
	Session s = customer("alice");
	AccountRef a = openAccount(s, "SAVINGS", 100);
	assertThat(put("/account/updateKyc/" + a.id(), s.token(),
		Map.of("name", "Alice New", "address", "", "identityProof", "")).status()).isEqualTo(200);
	JsonNode u = get("/account/getKycDetails?accountNumber=" + a.accountNumber(), s.token()).json();
	assertThat(u.get("name").asText()).isEqualTo("Alice New");
	assertThat(u.get("address").asText()).isEqualTo("1 Main St");
	assertThat(u.get("number").asLong()).isEqualTo(5551234567L);
	assertThat(u.get("identityProof").asText()).isEqualTo("PASSPORT-alice");

	assertThat(put("/account/updateKyc/" + a.id(), s.token(),
		Map.of("name", "", "address", "2 New Rd", "number", 42, "identityProof", "DL-9")).status())
		.isEqualTo(200);
	u = get("/account/getKycDetails?accountNumber=" + a.accountNumber(), s.token()).json();
	assertThat(u.get("name").asText()).isEqualTo("Alice New");
	assertThat(u.get("address").asText()).isEqualTo("2 New Rd");
	assertThat(u.get("number").asLong()).isEqualTo(42);
	assertThat(u.get("identityProof").asText()).isEqualTo("DL-9");
    }

    @Test
    void updateKycWithMissingStringFieldIs500() {
	Session s = customer("alice");
	AccountRef a = openAccount(s, "SAVINGS", 100);
	assertThat(put("/account/updateKyc/" + a.id(), s.token(), Map.of("address", "x", "identityProof", "y"))
		.status()).isEqualTo(500);
    }

    @Test
    void summaryHidesUserButKeepsCardAndNominee() {
	Session s = customer("alice");
	AccountRef a = openAccount(s, "SAVINGS", 100);
	HttpResult r = get("/account/getAccount/summary?accountNumber=" + a.accountNumber(), s.token());
	assertThat(r.status()).isEqualTo(200);
	JsonNode acc = r.json();
	assertThat(fieldNames(acc)).containsExactly("id", "accountType", "status", "balance", "interestRate",
		"branch", "proof", "openingDate", "accountNumber", "nominee", "card", "user");
	assertThat(acc.get("user").isNull()).isTrue();
	assertThat(acc.get("card").get("cardNumber").asLong()).isEqualTo(a.cardNumber());
	assertThat(acc.get("nominee").get("name").asText()).isEqualTo("Jane Nominee");
	assertThat(jdbc.queryForObject("SELECT user_id FROM account WHERE id = ?", Long.class, a.id()))
		.isEqualTo(s.userId());
    }
}
