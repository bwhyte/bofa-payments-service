package com.security.bank.characterization;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import com.security.bank.support.HttpResult;
import com.security.bank.support.IntegrationTestBase;

/**
 * Who can call what, today. Every endpoint is called anonymously, as a CUSTOMER and as an ADMIN.
 * A denied call must return 403 with the standard error body and must not change any data.
 */
class AccessControlMatrixTest extends IntegrationTestBase {

    enum Caller {
	ANONYMOUS, CUSTOMER, ADMIN
    }

    enum Rule {
	PUBLIC, AUTHENTICATED, CUSTOMER_ONLY, ADMIN_ONLY
    }

    record Fixture(Session customer, Session admin, AccountRef savings, AccountRef ppf) {
    }

    record Endpoint(String method, Function<Fixture, String> path, Function<Fixture, Object> body, Rule rule,
	    int successStatus) {
	static Endpoint of(String method, Function<Fixture, String> path, Function<Fixture, Object> body, Rule rule,
		int successStatus) {
	    return new Endpoint(method, path, body, rule, successStatus);
	}
    }

    static final Map<String, Endpoint> ENDPOINTS = new java.util.LinkedHashMap<>();
    static {
	ENDPOINTS.put("POST /user/register", Endpoint.of("POST", f -> "/user/register", f -> userBody("newuser"),
		Rule.PUBLIC, 201));
	ENDPOINTS.put("POST /user/login", Endpoint.of("POST", f -> "/user/login",
		f -> Map.of("username", f.customer().username(), "password", PASSWORD), Rule.PUBLIC, 200));
	ENDPOINTS.put("POST /admin/add", Endpoint.of("POST", f -> "/admin/add", f -> userBody("newadmin"),
		Rule.AUTHENTICATED, 201));
	ENDPOINTS.put("GET /admin/getAllUser",
		Endpoint.of("GET", f -> "/admin/getAllUser", f -> null, Rule.ADMIN_ONLY, 200));
	ENDPOINTS.put("GET /admin/getUserByName/{username}", Endpoint.of("GET",
		f -> "/admin/getUserByName/" + f.customer().username(), f -> null, Rule.ADMIN_ONLY, 200));
	ENDPOINTS.put("DELETE /admin/deleteUser/{userId}", Endpoint.of("DELETE",
		f -> "/admin/deleteUser/" + f.customer().userId(), f -> null, Rule.ADMIN_ONLY, 200));
	ENDPOINTS.put("PUT /admin/account/deactivate", Endpoint.of("PUT",
		f -> "/admin/account/deactivate?userId=" + f.customer().userId() + "&accountId=" + f.savings().id(),
		f -> null, Rule.ADMIN_ONLY, 202));
	ENDPOINTS.put("PUT /admin/account/activate", Endpoint.of("PUT",
		f -> "/admin/account/activate?userId=" + f.customer().userId() + "&accountId=" + f.savings().id(),
		f -> null, Rule.ADMIN_ONLY, 202));
	ENDPOINTS.put("GET /admin/account/getActiveAccountsList",
		Endpoint.of("GET", f -> "/admin/account/getActiveAccountsList", f -> null, Rule.ADMIN_ONLY, 200));
	ENDPOINTS.put("GET /admin/account/getInActiveAccountsList",
		Endpoint.of("GET", f -> "/admin/account/getInActiveAccountsList", f -> null, Rule.ADMIN_ONLY, 200));
	ENDPOINTS.put("GET /admin/accountList/ByAccountType/{accType}",
		Endpoint.of("GET", f -> "/admin/accountList/ByAccountType/SAVINGS", f -> null, Rule.ADMIN_ONLY, 200));
	ENDPOINTS.put("GET /admin/accountList/ByBranchType/{branchType}",
		Endpoint.of("GET", f -> "/admin/accountList/ByBranchType/BOB", f -> null, Rule.ADMIN_ONLY, 200));
	ENDPOINTS.put("POST /account/create/{userId}", Endpoint.of("POST",
		f -> "/account/create/" + f.customer().userId(), f -> accountBody("CURRENT", 500), Rule.CUSTOMER_ONLY,
		201));
	ENDPOINTS.put("GET /account/all/{userId}", Endpoint.of("GET", f -> "/account/all/" + f.customer().userId(),
		f -> null, Rule.CUSTOMER_ONLY, 200));
	ENDPOINTS.put("GET /account/balance", Endpoint.of("GET",
		f -> "/account/balance?accountNumber=" + f.savings().accountNumber(), f -> null, Rule.CUSTOMER_ONLY, 200));
	ENDPOINTS.put("GET /account/nominee", Endpoint.of("GET",
		f -> "/account/nominee?accountNumber=" + f.savings().accountNumber(), f -> null, Rule.CUSTOMER_ONLY, 200));
	ENDPOINTS.put("PUT /account/updateNominee/{accountId}",
		Endpoint.of("PUT", f -> "/account/updateNominee/" + f.savings().id(), f -> nominee(), Rule.CUSTOMER_ONLY,
			200));
	ENDPOINTS.put("GET /account/getKycDetails", Endpoint.of("GET",
		f -> "/account/getKycDetails?accountNumber=" + f.savings().accountNumber(), f -> null,
		Rule.CUSTOMER_ONLY, 200));
	ENDPOINTS.put("PUT /account/updateKyc/{accountId}",
		Endpoint.of("PUT", f -> "/account/updateKyc/" + f.savings().id(),
			f -> Map.of("name", "N", "address", "A", "number", 1L, "identityProof", "P"), Rule.CUSTOMER_ONLY,
			200));
	ENDPOINTS.put("GET /account/getAccount/summary", Endpoint.of("GET",
		f -> "/account/getAccount/summary?accountNumber=" + f.savings().accountNumber(), f -> null,
		Rule.CUSTOMER_ONLY, 200));
	ENDPOINTS.put("GET /card/block", Endpoint.of("GET",
		f -> "/card/block?accountNumber=" + f.savings().accountNumber() + "&cardNumber="
			+ f.savings().cardNumber(),
		f -> null, Rule.CUSTOMER_ONLY, 200));
	ENDPOINTS.put("POST /card/apply/new",
		Endpoint.of("POST", f -> "/card/apply/new?accountNumber=" + f.ppf().accountNumber(),
			f -> Map.of("cardHolderName", "Holder", "cardType", "DEBIT_CLASSIC", "pin", 4321),
			Rule.CUSTOMER_ONLY, 201));
	ENDPOINTS.put("PUT /card/setting", Endpoint.of("PUT", f -> "/card/setting?cardNumber=" + f.savings().cardNumber(),
		f -> Map.of("dailyLimit", 30000), Rule.CUSTOMER_ONLY, 200));
	ENDPOINTS.put("POST /invest/now", Endpoint.of("POST", f -> "/invest/now?accountId=" + f.savings().id(),
		f -> Map.of("investmentType", "GOLD", "amount", 100, "duration", "1y"), Rule.CUSTOMER_ONLY, 200));
    }

    static Stream<Arguments> matrix() {
	return ENDPOINTS.keySet().stream()
		.flatMap(name -> Stream.of(Caller.values()).map(caller -> Arguments.of(name, caller)));
    }

    static boolean allowed(Rule rule, Caller caller) {
	return switch (rule) {
	case PUBLIC -> true;
	case AUTHENTICATED -> caller != Caller.ANONYMOUS;
	case CUSTOMER_ONLY -> caller == Caller.CUSTOMER;
	case ADMIN_ONLY -> caller == Caller.ADMIN;
	};
    }

    private Fixture fixture() {
	Session customer = customer("alice");
	AccountRef savings = openAccount(customer, "SAVINGS", 10000);
	AccountRef ppf = openAccount(customer, "PPF", 2000);
	Session admin = admin("root");
	return new Fixture(customer, admin, savings, ppf);
    }

    private List<Integer> rowCounts() {
	return Stream.of("user", "role", "account", "card", "nominee", "investment").map(this::count).toList();
    }

    @ParameterizedTest(name = "{0} as {1}")
    @MethodSource("matrix")
    void accessRule(String endpointName, Caller caller) {
	Endpoint e = ENDPOINTS.get(endpointName);
	Fixture f = fixture();
	String token = switch (caller) {
	case ANONYMOUS -> null;
	case CUSTOMER -> f.customer().token();
	case ADMIN -> f.admin().token();
	};
	List<Integer> before = rowCounts();
	String path = e.path().apply(f);

	HttpResult r = send(e.method(), path, token, e.body().apply(f));

	if (allowed(e.rule(), caller)) {
	    assertThat(r.status()).as("%s as %s: %s", endpointName, caller, r.body()).isEqualTo(e.successStatus());
	} else {
	    assertThat(r.status()).as("%s as %s: %s", endpointName, caller, r.body()).isEqualTo(403);
	    assertThat(r.body()).as("denied responses have an empty body").isEmpty();
	    assertThat(r.header("Content-Length")).isEqualTo("0");
	    assertThat(rowCounts()).as("denied call must not change data").isEqualTo(before);
	}
    }

    @Test
    void everyControllerEndpointIsInTheMatrix() {
	assertThat(ENDPOINTS).hasSize(24);
    }

    @Test
    void unknownPathsRequireAuthentication() {
	assertThat(get("/does-not-exist", null).status()).isEqualTo(403);
	assertThat(get("/", null).status()).isEqualTo(403);
	assertThat(get("/error", null).status()).isEqualTo(403);
    }

    @Test
    void onlyExactRegisterAndLoginPathsArePublic() {
	customer("bob");
	assertThat(post("/user/register/", null, userBody("carol")).status()).isEqualTo(403);
	assertThat(post("/user/login/", null, Map.of("username", "bob", "password", PASSWORD)).status())
		.isEqualTo(403);
	assertThat(post("/USER/login", null, Map.of("username", "bob", "password", PASSWORD)).status())
		.isEqualTo(403);
	assertThat(get("/user/other", null).status()).isEqualTo(403);
    }
}
