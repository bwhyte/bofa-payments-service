package com.security.bank.characterization;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.security.bank.support.HttpResult;
import com.security.bank.support.IntegrationTestBase;

/** /admin/** behaviour for an ADMIN caller. */
class AdminBehaviourTest extends IntegrationTestBase {

    private static List<String> names(JsonNode array, String field) {
	List<String> out = new ArrayList<>();
	array.forEach(n -> out.add(n.get(field).asText()));
	return out;
    }

    @Test
    void adminAddCreatesAdminRoleAndReturns201() {
	Session bootstrap = customer("bootstrap");
	HttpResult r = post("/admin/add", bootstrap.token(), userBody("root"));
	assertThat(r.status()).isEqualTo(201);
	assertThat(r.body()).isEmpty();
	assertThat(jdbc.queryForObject(
		"SELECT r.role_name FROM user u JOIN role r ON r.id = u.roles_id WHERE u.username = 'root'",
		String.class)).isEqualTo("ROLE_ADMIN");
	assertThat(jdbc.queryForObject("SELECT password FROM user WHERE username = 'root'", String.class))
		.startsWith("$2a$10$");
    }

    @Test
    void getAllUsersReturnsEveryUserWithPasswordHashesAndAccounts() {
	Session alice = customer("alice");
	openAccount(alice, "SAVINGS", 100);
	Session admin = admin("root");
	HttpResult r = get("/admin/getAllUser", admin.token());
	assertThat(r.status()).isEqualTo(200);
	JsonNode users = r.json();
	assertThat(names(users, "username")).containsExactly("alice", "root-bootstrap", "root");
	JsonNode u = users.get(0);
	assertThat(AccountBehaviourTest.fieldNames(u)).containsExactlyInAnyOrder("id", "name", "username", "password",
		"address", "number", "identityProof", "roles", "accountList", "investmentList", "authorities",
		"enabled", "accountNonExpired", "accountNonLocked", "credentialsNonExpired");
	assertThat(u.get("password").asText()).startsWith("$2a$10$");
	assertThat(u.get("accountList").size()).isEqualTo(1);
	assertThat(u.get("accountList").get(0).has("user")).isFalse();
	assertThat(u.get("accountList").get(0).get("card").get("pin").asLong()).isEqualTo(1122L);
	assertThat(names(users, "roles").size()).isEqualTo(3);
	assertThat(users.get(2).get("roles").get("roleName").asText()).isEqualTo("ROLE_ADMIN");
    }

    @Test
    void getUserByName() {
	customer("alice");
	Session admin = admin("root");
	JsonNode u = get("/admin/getUserByName/alice", admin.token()).json();
	assertThat(u.get("username").asText()).isEqualTo("alice");
	assertThat(u.get("accountList").isArray()).isTrue();
	assertThat(get("/admin/getUserByName/ghost", admin.token()).status()).isEqualTo(500);
    }

    @Test
    void deleteUserCascadesToAccountsCardsNomineesRolesAndInvestments() {
	Session alice = customer("alice");
	AccountRef a = openAccount(alice, "SAVINGS", 1000);
	post("/invest/now?accountId=" + a.id(), alice.token(),
		java.util.Map.of("investmentType", "GOLD", "amount", 1, "duration", "1y"));
	Session admin = admin("root");
	int roles = count("role");

	HttpResult r = delete("/admin/deleteUser/" + alice.userId(), admin.token());
	assertThat(r.status()).isEqualTo(200);
	assertThat(r.body()).isEqualTo("Deleted Successfully");
	assertThat(count("user")).isEqualTo(2);
	assertThat(count("account")).isZero();
	assertThat(count("card")).isZero();
	assertThat(count("nominee")).isZero();
	assertThat(count("investment")).isZero();
	assertThat(count("role")).isEqualTo(roles - 1);
    }

    @Test
    void deleteUnknownUserIs200WithErrorText() {
	Session admin = admin("root");
	HttpResult r = delete("/admin/deleteUser/999", admin.token());
	assertThat(r.status()).isEqualTo(200);
	assertThat(r.body()).isEqualTo("Error in deletion");
    }

    @Test
    void adminCanDeleteThemselves() {
	Session admin = admin("root");
	assertThat(delete("/admin/deleteUser/" + admin.userId(), admin.token()).body())
		.isEqualTo("Deleted Successfully");
    }

    @Test
    void deactivateAndActivateAccount() {
	Session alice = customer("alice");
	AccountRef a = openAccount(alice, "SAVINGS", 100);
	Session admin = admin("root");
	String q = "?userId=" + alice.userId() + "&accountId=" + a.id();

	HttpResult r = put("/admin/account/deactivate" + q, admin.token(), null);
	assertThat(r.status()).isEqualTo(202);
	assertThat(r.body()).isEqualTo("Deactivated Account for User with id: " + alice.userId());
	assertThat(jdbc.queryForObject("SELECT status FROM account WHERE id = ?", String.class, a.id()))
		.isEqualTo("INACTIVE");
	assertThat(names(get("/admin/account/getInActiveAccountsList", admin.token()).json(), "accountNumber"))
		.containsExactly(String.valueOf(a.accountNumber()));
	assertThat(get("/admin/account/getActiveAccountsList", admin.token()).json().size()).isZero();

	assertThat(put("/admin/account/deactivate" + q, admin.token(), null).body())
		.isEqualTo("Deactivated Account for User with id: " + alice.userId());

	r = put("/admin/account/activate" + q, admin.token(), null);
	assertThat(r.status()).isEqualTo(202);
	assertThat(r.body()).isEqualTo("Activated Account for User with id: " + alice.userId());
	assertThat(put("/admin/account/activate" + q, admin.token(), null).body()).isEqualTo("ERROR");
	assertThat(get("/admin/account/getActiveAccountsList", admin.token()).json().size()).isEqualTo(1);
    }

    @Test
    void inactiveAccountsStillWorkForCustomers() {
	Session alice = customer("alice");
	AccountRef a = openAccount(alice, "SAVINGS", 100);
	Session admin = admin("root");
	put("/admin/account/deactivate?userId=" + alice.userId() + "&accountId=" + a.id(), admin.token(), null);
	assertThat(get("/account/balance?accountNumber=" + a.accountNumber(), alice.token()).status()).isEqualTo(200);
	assertThat(post("/invest/now?accountId=" + a.id(), alice.token(),
		java.util.Map.of("investmentType", "GOLD", "amount", 1, "duration", "1y")).status()).isEqualTo(200);
    }

    @Test
    void deactivateWithMismatchedOwnerReportsSuccessButChangesNothing() {
	Session alice = customer("alice");
	Session bob = customer("bob");
	AccountRef a = openAccount(alice, "SAVINGS", 100);
	Session admin = admin("root");
	HttpResult r = put("/admin/account/deactivate?userId=" + bob.userId() + "&accountId=" + a.id(), admin.token(),
		null);
	assertThat(r.status()).isEqualTo(202);
	assertThat(r.body()).isEqualTo("Deactivated Account for User with id: " + bob.userId());
	assertThat(jdbc.queryForObject("SELECT status FROM account WHERE id = ?", String.class, a.id()))
		.isEqualTo("ACTIVE");
    }

    @Test
    void deactivateOrActivateUnknownIdsReturnsErrorText() {
	Session admin = admin("root");
	assertThat(put("/admin/account/deactivate?userId=999&accountId=999", admin.token(), null).body())
		.isEqualTo("ERROR");
	assertThat(put("/admin/account/activate?userId=999&accountId=999", admin.token(), null).body())
		.isEqualTo("ERROR");
	assertThat(put("/admin/account/activate?userId=999&accountId=999", admin.token(), null).status())
		.isEqualTo(202);
    }

    @Test
    void accountListsIncludeOwningUserWithoutAccountList() {
	Session alice = customer("alice");
	openAccount(alice, "SAVINGS", 100);
	openAccount(alice, "CURRENT", 100);
	openAccount(alice, "PPF", 100);
	Session admin = admin("root");

	JsonNode active = get("/admin/account/getActiveAccountsList", admin.token()).json();
	assertThat(active.size()).isEqualTo(3);
	JsonNode owner = active.get(0).get("user");
	assertThat(owner.get("username").asText()).isEqualTo("alice");
	assertThat(owner.has("accountList")).isFalse();
	assertThat(owner.get("password").asText()).startsWith("$2a$10$");
	assertThat(owner.get("investmentList").isArray()).isTrue();

	assertThat(names(get("/admin/accountList/ByAccountType/CURRENT", admin.token()).json(), "accountType"))
		.containsExactly("CURRENT");
	assertThat(names(get("/admin/accountList/ByBranchType/SBI", admin.token()).json(), "accountType"))
		.containsExactly("PPF");
	assertThat(get("/admin/accountList/ByBranchType/HDFC", admin.token()).json().size()).isZero();
	assertThat(get("/admin/account/getInActiveAccountsList", admin.token()).body()).isEqualTo("[]");
    }
}
