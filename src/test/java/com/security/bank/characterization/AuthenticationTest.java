package com.security.bank.characterization;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Date;
import java.util.Map;

import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.util.ReflectionTestUtils;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.security.bank.jwt.JwtAuthenticationHelper;
import com.security.bank.support.HttpResult;
import com.security.bank.support.IntegrationTestBase;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;

/** Registration, login and JWT handling as clients see it today. */
class AuthenticationTest extends IntegrationTestBase {

    @Autowired
    JwtAuthenticationHelper jwtHelper;

    private String secret() {
	return (String) ReflectionTestUtils.getField(jwtHelper, "secret");
    }

    private String signedToken(String subject, Date issuedAt, Date expiry, byte[] key) {
	return Jwts.builder().setSubject(subject).setIssuedAt(issuedAt).setExpiration(expiry)
		.signWith(new SecretKeySpec(key, SignatureAlgorithm.HS512.getJcaName()), SignatureAlgorithm.HS512)
		.compact();
    }

    private static JsonNode decodePart(String token, int part) throws Exception {
	String[] parts = token.split("\\.");
	return new ObjectMapper().readTree(Base64.getUrlDecoder().decode(parts[part]));
    }

    // ---------- register ----------

    @Test
    void registerReturns201WithEmptyBodyAndStoresBcryptCustomer() {
	HttpResult r = register("alice");
	assertThat(r.status()).isEqualTo(201);
	assertThat(r.body()).isEmpty();

	Map<String, Object> row = jdbc.queryForMap(
		"SELECT u.name, u.username, u.password, u.address, u.number, u.identity_proof, r.role_name "
			+ "FROM user u JOIN role r ON r.id = u.roles_id WHERE u.username = 'alice'");
	assertThat(row.get("NAME")).isEqualTo("Name alice");
	assertThat(row.get("ADDRESS")).isEqualTo("1 Main St");
	assertThat(row.get("NUMBER")).isEqualTo(5551234567L);
	assertThat(row.get("IDENTITY_PROOF")).isEqualTo("PASSPORT-alice");
	assertThat(row.get("ROLE_NAME")).isEqualTo("ROLE_CUSTOMER");
	assertThat((String) row.get("PASSWORD")).startsWith("$2a$10$").hasSize(60);
    }

    @Test
    void eachRegistrationCreatesItsOwnRoleRow() {
	register("alice");
	register("bob");
	assertThat(count("user")).isEqualTo(2);
	assertThat(count("role")).isEqualTo(2);
    }

    @Test
    void duplicateUsernameIsAcceptedButThenLoginFails() {
	assertThat(register("alice").status()).isEqualTo(201);
	assertThat(register("alice").status()).isEqualTo(201);
	assertThat(count("user")).isEqualTo(2);
	assertThat(login("alice", PASSWORD).status()).isEqualTo(403);
    }

    @Test
    void registerWithoutPasswordIsServerError() {
	HttpResult r = post("/user/register", null, Map.of("username", "nopass"));
	assertThat(r.status()).isEqualTo(500);
	assertThat(count("user")).isZero();
    }

    @Test
    void registerIgnoresRoleFieldsInBody() {
	Map<String, Object> body = new java.util.HashMap<>(userBody("sneaky"));
	body.put("roles", Map.of("roleName", "ROLE_ADMIN"));
	body.put("userType", "ADMIN");
	assertThat(post("/user/register", null, body).status()).isEqualTo(201);
	assertThat(jdbc.queryForObject(
		"SELECT r.role_name FROM user u JOIN role r ON r.id = u.roles_id WHERE u.username = 'sneaky'",
		String.class)).isEqualTo("ROLE_CUSTOMER");
    }

    @Test
    void registerWorksWithAValidTokenToo() {
	Session s = customer("alice");
	assertThat(post("/user/register", s.token(), userBody("bob")).status()).isEqualTo(201);
    }

    // ---------- login ----------

    @Test
    void loginReturnsOnlyAJwtToken() throws Exception {
	register("alice");
	HttpResult r = login("alice", PASSWORD);
	assertThat(r.status()).isEqualTo(200);
	assertThat(r.header("Content-Type")).isEqualTo("application/json");
	JsonNode body = r.json();
	assertThat(body.size()).isEqualTo(1);
	String token = body.get("jwtToken").asText();
	assertThat(token.split("\\.")).hasSize(3);

	JsonNode header = decodePart(token, 0);
	assertThat(header.get("alg").asText()).isEqualTo("HS512");
	JsonNode claims = decodePart(token, 1);
	assertThat(claims.get("sub").asText()).isEqualTo("alice");
	assertThat(claims.get("exp").asLong() - claims.get("iat").asLong()).isEqualTo(3600);
	assertThat(claims.size()).as("claims: %s", claims).isEqualTo(3);
	assertThat(claims.has("roles")).isFalse();
    }

    @Test
    void loginWithWrongPasswordIsForbidden() {
	register("alice");
	HttpResult r = login("alice", "wrong");
	assertThat(r.status()).isEqualTo(403);
	assertThat(r.body()).isEmpty();
    }

    @Test
    void loginWithUnknownUserIsForbidden() {
	HttpResult r = login("ghost", PASSWORD);
	assertThat(r.status()).isEqualTo(403);
	assertThat(r.body()).isEmpty();
    }

    @Test
    void loginWithEmptyBodyIsForbidden() {
	assertThat(post("/user/login", null, Map.of()).status()).isEqualTo(403);
    }

    @Test
    void loginWithGetIsMethodNotAllowed() {
	HttpResult r = get("/user/login", null);
	assertThat(r.status()).isEqualTo(405);
	assertThat(r.json().get("error").asText()).isEqualTo("Method Not Allowed");
    }

    @Test
    void adminLoginTokenHasTheSameShape() throws Exception {
	Session admin = admin("root");
	JsonNode claims = decodePart(admin.token(), 1);
	assertThat(claims.get("sub").asText()).isEqualTo("root");
	assertThat(claims.size()).isEqualTo(3);
    }

    // ---------- JWT on protected endpoints ----------

    @Test
    void validTokenAuthenticates() {
	Session s = customer("alice");
	HttpResult r = get("/account/all/" + s.userId(), s.token());
	assertThat(r.status()).isEqualTo(200);
	assertThat(r.body()).isEqualTo("[]");
    }

    @Test
    void tokenSignedByTestWithAppSecretIsAccepted() {
	Session s = customer("alice");
	long now = System.currentTimeMillis();
	String forged = signedToken("alice", new Date(now), new Date(now + 60_000), secret().getBytes());
	assertThat(get("/account/all/" + s.userId(), forged).status()).isEqualTo(200);
    }

    @Test
    void tokenWithoutExpiryIsServerError() {
	Session s = customer("alice");
	String token = Jwts.builder().setSubject("alice")
		.signWith(new SecretKeySpec(secret().getBytes(), "HmacSHA512"), SignatureAlgorithm.HS512).compact();
	assertThat(get("/account/all/" + s.userId(), token).status()).isEqualTo(500);
    }

    @Test
    void expiredTokenIsServerError() {
	Session s = customer("alice");
	long now = System.currentTimeMillis();
	String expired = signedToken("alice", new Date(now - 7_200_000), new Date(now - 3_600_000),
		secret().getBytes());
	HttpResult r = get("/account/all/" + s.userId(), expired);
	assertThat(r.status()).isEqualTo(500);
	assertThat(r.json().get("error").asText()).isEqualTo("Internal Server Error");
	assertThat(r.json().has("message")).isFalse();
	assertThat(r.json().has("trace")).isFalse();
    }

    @Test
    void tokenSignedWithAnotherKeyIsServerError() {
	Session s = customer("alice");
	long now = System.currentTimeMillis();
	byte[] otherKey = "a-completely-different-secret-key-that-is-long-enough-for-hs512-signing-ok"
		.getBytes(StandardCharsets.UTF_8);
	String token = signedToken("alice", new Date(now), new Date(now + 60_000), otherKey);
	assertThat(get("/account/all/" + s.userId(), token).status()).isEqualTo(500);
    }

    @Test
    void tamperedTokenIsServerError() {
	Session s = customer("alice");
	String[] p = s.token().split("\\.");
	String payload = Base64.getUrlEncoder().withoutPadding()
		.encodeToString(("{\"sub\":\"root\",\"iat\":1,\"exp\":99999999999}").getBytes());
	assertThat(get("/account/all/" + s.userId(), p[0] + "." + payload + "." + p[2]).status()).isEqualTo(500);
    }

    @Test
    void garbageTokenIsServerError() {
	Session s = customer("alice");
	assertThat(get("/account/all/" + s.userId(), "not-a-jwt").status()).isEqualTo(500);
    }

    @Test
    void tokenForDeletedUserIsServerError() {
	Session s = customer("alice");
	Session admin = admin("root");
	assertThat(delete("/admin/deleteUser/" + s.userId(), admin.token()).status()).isEqualTo(200);
	assertThat(get("/account/all/" + s.userId(), s.token()).status()).isEqualTo(500);
    }

    @Test
    void badTokenBreaksEvenPublicEndpoints() {
	assertThat(send("POST", "/user/register", "not-a-jwt", userBody("alice")).status()).isEqualTo(500);
	assertThat(count("user")).isZero();
    }

    @Test
    void bareBearerHeaderIsServerError() {
	assertThat(withAuthHeader("GET", "/account/all/1", "Bearer").status()).isEqualTo(500);
    }

    @Test
    void bearerWithoutSpaceDropsFirstCharacterOfToken() {
	Session s = customer("alice");
	assertThat(withAuthHeader("GET", "/account/all/" + s.userId(), "Bearer" + s.token()).status())
		.isEqualTo(500);
	assertThat(withAuthHeader("GET", "/account/all/" + s.userId(), "BearerX" + s.token()).status())
		.isEqualTo(200);
    }

    @Test
    void nonBearerSchemesAreTreatedAsAnonymous() {
	Session s = customer("alice");
	assertThat(withAuthHeader("GET", "/account/all/" + s.userId(), "Basic YWxpY2U6U2VjcmV0MTIzIQ==").status())
		.isEqualTo(403);
	assertThat(withAuthHeader("GET", "/account/all/" + s.userId(), "bearer " + s.token()).status())
		.isEqualTo(403);
    }

    @Test
    void roleIsReadFromDatabaseNotToken() {
	Session s = customer("alice");
	jdbc.update("UPDATE role SET role_name = 'ROLE_ADMIN' WHERE id = (SELECT roles_id FROM user WHERE id = ?)",
		s.userId());
	assertThat(get("/admin/getAllUser", s.token()).status()).isEqualTo(200);
	assertThat(get("/account/all/" + s.userId(), s.token()).status()).isEqualTo(403);
    }

    @Test
    void noSessionCookieIsIssued() {
	Session s = customer("alice");
	assertThat(login("alice", PASSWORD).headers().allValues("Set-Cookie")).isEmpty();
	assertThat(get("/account/all/" + s.userId(), s.token()).headers().allValues("Set-Cookie")).isEmpty();
	assertThat(get("/account/all/" + s.userId(), null).headers().allValues("Set-Cookie")).isEmpty();
    }
}
