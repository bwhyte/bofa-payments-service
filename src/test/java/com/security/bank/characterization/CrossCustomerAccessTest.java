package com.security.bank.characterization;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;

import com.security.bank.support.IntegrationTestBase;

/**
 * Pins existing (insecure) behaviour: there is no ownership check, so any CUSTOMER can act on any
 * other customer's user id, account number, account id or card number; and any authenticated user
 * can create admins. These are flagged for human review; the upgrade must not change them silently.
 */
class CrossCustomerAccessTest extends IntegrationTestBase {

    @Test
    void customerCanReadAndModifyAnotherCustomersData() {
	Session alice = customer("alice");
	AccountRef a = openAccount(alice, "SAVINGS", 5000);
	Session mallory = customer("mallory");

	assertThat(get("/account/all/" + alice.userId(), mallory.token()).status()).isEqualTo(200);
	assertThat(get("/account/balance?accountNumber=" + a.accountNumber(), mallory.token()).body())
		.isEqualTo("5000.0");
	assertThat(get("/account/getKycDetails?accountNumber=" + a.accountNumber(), mallory.token()).json()
		.get("password").asText()).startsWith("$2a$10$");
	assertThat(put("/account/updateNominee/" + a.id(), mallory.token(), nominee()).status()).isEqualTo(200);
	assertThat(put("/account/updateKyc/" + a.id(), mallory.token(),
		Map.of("name", "Owned", "address", "", "identityProof", "")).status()).isEqualTo(200);
	assertThat(put("/card/setting?cardNumber=" + a.cardNumber(), mallory.token(), Map.of("pin", 1)).status())
		.isEqualTo(200);
	assertThat(post("/invest/now?accountId=" + a.id(), mallory.token(),
		Map.of("investmentType", "GOLD", "amount", 1, "duration", "1y")).status()).isEqualTo(200);
	assertThat(post("/account/create/" + alice.userId(), mallory.token(), accountBody("PPF", 1)).status())
		.isEqualTo(201);
	assertThat(get("/card/block?accountNumber=" + a.accountNumber() + "&cardNumber=" + a.cardNumber(),
		mallory.token()).body()).isEqualTo("Card Blocked Successfully");

	assertThat(jdbc.queryForObject("SELECT name FROM user WHERE id = ?", String.class, alice.userId()))
		.isEqualTo("Owned");
	assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM account WHERE user_id = ?", Integer.class,
		alice.userId())).isEqualTo(2);
    }

    @Test
    void anyCustomerCanCreateAnAdminAndThenUseAdminEndpoints() {
	Session mallory = customer("mallory");
	assertThat(post("/admin/add", mallory.token(), userBody("evil-admin")).status()).isEqualTo(201);
	String adminToken = loginToken("evil-admin");
	assertThat(get("/admin/getAllUser", adminToken).status()).isEqualTo(200);
    }

    @Test
    void anonymousCannotCreateAnAdmin() {
	assertThat(post("/admin/add", null, userBody("evil-admin")).status()).isEqualTo(403);
	assertThat(count("user")).isZero();
    }
}
