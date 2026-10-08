package com.security.bank.characterization;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.security.bank.support.HttpResult;
import com.security.bank.support.IntegrationTestBase;

/** Framework-level HTTP behaviour: error bodies, status codes, headers, path matching. */
class WebBehaviourTest extends IntegrationTestBase {

    private static List<String> fieldNames(JsonNode n) {
	List<String> names = new ArrayList<>();
	n.fieldNames().forEachRemaining(names::add);
	return names;
    }

    private void assertErrorBody(HttpResult r, int status, String error, String path) {
	assertThat(r.status()).as(r.body()).isEqualTo(status);
	assertThat(r.header("Content-Type")).isEqualTo("application/json");
	JsonNode body = r.json();
	assertThat(fieldNames(body)).containsExactly("timestamp", "status", "error", "path");
	assertThat(body.get("status").asInt()).isEqualTo(status);
	assertThat(body.get("error").asText()).isEqualTo(error);
	assertThat(body.get("path").asText()).isEqualTo(path);
	assertThat(body.get("timestamp").asText()).matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}[+-]\\d{2}:\\d{2}");
    }

    private static void assertEmptyForbidden(HttpResult r) {
	assertThat(r.status()).isEqualTo(403);
	assertThat(r.body()).isEmpty();
	assertThat(r.header("Content-Length")).isEqualTo("0");
	assertThat(r.header("Content-Type")).isNull();
    }

    @Test
    void anonymousForbiddenHasEmptyBody() {
	assertEmptyForbidden(get("/account/all/1", null));
    }

    @Test
    void wrongRoleForbiddenHasEmptyBody() {
	Session s = customer("alice");
	assertEmptyForbidden(get("/admin/getAllUser", s.token()));
    }

    @Test
    void failedLoginForbiddenHasEmptyBody() {
	customer("alice");
	assertEmptyForbidden(login("alice", "wrong"));
	assertEmptyForbidden(login("ghost", "wrong"));
    }

    @Test
    void badJwtGivesJson500() {
	assertErrorBody(get("/account/all/1", "garbage"), 500, "Internal Server Error", "/account/all/1");
    }

    @Test
    void unknownPathWhenAuthenticatedIs404() {
	Session s = customer("alice");
	assertErrorBody(get("/does-not-exist", s.token()), 404, "Not Found", "/does-not-exist");
    }

    @Test
    void missingRequestParamIs400() {
	Session s = customer("alice");
	assertErrorBody(get("/account/balance", s.token()), 400, "Bad Request", "/account/balance");
    }

    @Test
    void badPathVariableTypeIs400() {
	Session s = customer("alice");
	assertErrorBody(get("/account/all/abc", s.token()), 400, "Bad Request", "/account/all/abc");
	Session admin = admin("root");
	assertErrorBody(get("/admin/accountList/ByAccountType/savings", admin.token()), 400, "Bad Request",
		"/admin/accountList/ByAccountType/savings");
	assertErrorBody(get("/admin/accountList/ByBranchType/NOPE", admin.token()), 400, "Bad Request",
		"/admin/accountList/ByBranchType/NOPE");
    }

    @Test
    void malformedJsonIs400() {
	assertErrorBody(send("POST", "/user/register", null, "{not json", JSON, null), 400, "Bad Request",
		"/user/register");
    }

    @Test
    void wrongContentTypeIs415() {
	assertErrorBody(send("POST", "/user/register", null, "x", "text/plain", null), 415, "Unsupported Media Type",
		"/user/register");
    }

    @Test
    void wrongMethodIs405() {
	Session s = customer("alice");
	HttpResult r = delete("/account/all/" + s.userId(), s.token());
	assertErrorBody(r, 405, "Method Not Allowed", "/account/all/" + s.userId());
	assertThat(r.header("Allow")).isEqualTo("GET");
    }

    @Test
    void unhandledExceptionIs500WithoutMessageOrTrace() {
	Session s = customer("alice");
	assertErrorBody(get("/account/balance?accountNumber=1", s.token()), 500, "Internal Server Error",
		"/account/balance");
    }

    @Test
    void trailingSlashOnPublicEndpointsIsNotPublic() {
	customer("alice");
	assertEmptyForbidden(post("/user/login/", null, Map.of("username", "alice", "password", PASSWORD)));
	assertEmptyForbidden(post("/user/register/", null, userBody("bob")));
	assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM user", Integer.class)).isEqualTo(1);
    }

    @Test
    void trailingSlashMatchesTheSameHandler() {
	Session s = customer("alice");
	AccountRef a = openAccount(s, "SAVINGS", 100);
	HttpResult r = get("/account/all/" + s.userId() + "/", s.token());
	assertThat(r.status()).isEqualTo(200);
	assertThat(r.json().get(0).get("accountNumber").asLong()).isEqualTo(a.accountNumber());
	assertThat(get("/account/balance/?accountNumber=" + a.accountNumber(), s.token()).body()).isEqualTo("100.0");
    }

    @Test
    void securityHeadersOnSuccessfulResponse() {
	Session s = customer("alice");
	HttpResult r = get("/account/all/" + s.userId(), s.token());
	assertThat(r.header("X-Content-Type-Options")).isEqualTo("nosniff");
	assertThat(r.header("X-XSS-Protection")).isEqualTo("1; mode=block");
	assertThat(r.header("Cache-Control")).isEqualTo("no-cache, no-store, max-age=0, must-revalidate");
	assertThat(r.header("Pragma")).isEqualTo("no-cache");
	assertThat(r.header("Expires")).isEqualTo("0");
	assertThat(r.header("X-Frame-Options")).isEqualTo("DENY");
	assertThat(r.header("Strict-Transport-Security")).isNull();
	assertThat(r.header("Access-Control-Allow-Origin")).isNull();
    }

    @Test
    void securityHeadersOnForbiddenResponse() {
	HttpResult r = get("/account/all/1", null);
	assertThat(r.header("X-Content-Type-Options")).isEqualTo("nosniff");
	assertThat(r.header("X-XSS-Protection")).isEqualTo("1; mode=block");
	assertThat(r.header("X-Frame-Options")).isEqualTo("DENY");
    }

    @Test
    void corsPreflightIsNotHandled() {
	HttpResult r = send("OPTIONS", "/user/login", null, null, null,
		Map.of("Origin", "http://evil.example", "Access-Control-Request-Method", "POST"));
	assertThat(r.header("Access-Control-Allow-Origin")).isNull();
    }

    @Test
    void stringResponsesAreTextPlain() {
	Session s = customer("alice");
	AccountRef a = openAccount(s, "SAVINGS", 5000);
	HttpResult r = post("/invest/now?accountId=" + a.id(), s.token(),
		Map.of("investmentType", "GOLD", "amount", 10, "duration", "1y"));
	assertThat(r.header("Content-Type")).isEqualTo("text/plain;charset=UTF-8");
    }

    @Test
    void doubleResponsesAreJsonNumbers() {
	Session s = customer("alice");
	AccountRef a = openAccount(s, "SAVINGS", 1234.5);
	HttpResult r = get("/account/balance?accountNumber=" + a.accountNumber(), s.token());
	assertThat(r.header("Content-Type")).isEqualTo("application/json");
	assertThat(r.body()).isEqualTo("1234.5");
    }

    @Test
    void unknownJsonPropertiesAreIgnored() {
	Map<String, Object> body = new java.util.HashMap<>(userBody("alice"));
	body.put("unexpected", "value");
	assertThat(post("/user/register", null, body).status()).isEqualTo(201);
    }
}
