package com.security.bank.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Black-box HTTP harness for characterisation tests. Uses the JDK HttpClient
 * (not TestRestTemplate) so the client stays identical across Spring Boot
 * versions, and a real server port so the servlet error dispatch is exercised.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
public abstract class IntegrationTestBase {

    protected static final String JSON = "application/json";
    protected static final String PASSWORD = "Secret123!";

    private static final HttpClient CLIENT = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
	    .connectTimeout(Duration.ofSeconds(10)).build();
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @LocalServerPort
    protected int port;

    @Autowired
    protected JdbcTemplate jdbc;

    public record Session(long userId, String username, String token) {
    }

    public record AccountRef(long id, long accountNumber, Long cardNumber) {
    }

    @BeforeEach
    @AfterEach
    void resetDatabase() {
	List<String> tables = jdbc.queryForList(
		"SELECT TABLE_NAME FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA = 'PUBLIC' AND TABLE_TYPE = 'BASE TABLE'",
		String.class);
	jdbc.execute("SET REFERENTIAL_INTEGRITY FALSE");
	for (String table : tables) {
	    jdbc.execute("TRUNCATE TABLE \"PUBLIC\".\"" + table + "\" RESTART IDENTITY");
	}
	jdbc.execute("SET REFERENTIAL_INTEGRITY TRUE");
    }

    // ---------- raw HTTP ----------

    protected HttpResult send(String method, String path, String token, String body, String contentType,
	    Map<String, String> extraHeaders) {
	try {
	    HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
		    .timeout(Duration.ofSeconds(30));
	    if (contentType != null) {
		b.header("Content-Type", contentType);
	    }
	    if (token != null) {
		b.header("Authorization", "Bearer " + token);
	    }
	    if (extraHeaders != null) {
		extraHeaders.forEach(b::header);
	    }
	    b.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
		    : HttpRequest.BodyPublishers.ofString(body));
	    HttpResponse<String> r = CLIENT.send(b.build(), HttpResponse.BodyHandlers.ofString());
	    return new HttpResult(r.statusCode(), r.headers(), r.body());
	} catch (Exception e) {
	    throw new IllegalStateException(e);
	}
    }

    protected HttpResult send(String method, String path, String token, Object body) {
	return send(method, path, token, body == null ? null : toJson(body), body == null ? null : JSON, null);
    }

    protected HttpResult get(String path, String token) {
	return send("GET", path, token, null);
    }

    protected HttpResult post(String path, String token, Object body) {
	return send("POST", path, token, body);
    }

    protected HttpResult put(String path, String token, Object body) {
	return send("PUT", path, token, body);
    }

    protected HttpResult delete(String path, String token) {
	return send("DELETE", path, token, null);
    }

    protected HttpResult withAuthHeader(String method, String path, String authorizationHeader) {
	return send(method, path, null, null, null, Map.of("Authorization", authorizationHeader));
    }

    protected static String toJson(Object o) {
	if (o instanceof String s) {
	    return s;
	}
	try {
	    return MAPPER.writeValueAsString(o);
	} catch (Exception e) {
	    throw new IllegalStateException(e);
	}
    }

    // ---------- fixtures (all via the public API) ----------

    protected static Map<String, Object> userBody(String username) {
	Map<String, Object> m = new LinkedHashMap<>();
	m.put("name", "Name " + username);
	m.put("username", username);
	m.put("password", PASSWORD);
	m.put("address", "1 Main St");
	m.put("number", 5551234567L);
	m.put("identityProof", "PASSPORT-" + username);
	return m;
    }

    protected HttpResult register(String username) {
	return post("/user/register", null, userBody(username));
    }

    protected HttpResult login(String username, String password) {
	return post("/user/login", null, Map.of("username", username, "password", password));
    }

    protected String loginToken(String username) {
	HttpResult r = login(username, PASSWORD);
	assertThat(r.status()).as("login %s: %s", username, r.body()).isEqualTo(200);
	return r.json().get("jwtToken").asText();
    }

    protected long userId(String username) {
	return jdbc.queryForObject("SELECT id FROM user WHERE username = ?", Long.class, username);
    }

    protected Session customer(String username) {
	HttpResult r = register(username);
	assertThat(r.status()).as("register %s: %s", username, r.body()).isEqualTo(201);
	return new Session(userId(username), username, loginToken(username));
    }

    /** Admins can only be created through POST /admin/add, which needs some authenticated caller. */
    protected Session admin(String username) {
	Session bootstrap = customer(username + "-bootstrap");
	HttpResult r = post("/admin/add", bootstrap.token(), userBody(username));
	assertThat(r.status()).as("admin/add %s: %s", username, r.body()).isEqualTo(201);
	return new Session(userId(username), username, loginToken(username));
    }

    protected static Map<String, Object> nominee() {
	Map<String, Object> n = new LinkedHashMap<>();
	n.put("relation", "Spouse");
	n.put("name", "Jane Nominee");
	n.put("accountNumber", 99887766L);
	n.put("gender", "F");
	n.put("age", 34);
	return n;
    }

    protected static Map<String, Object> accountBody(String type, double balance) {
	Map<String, Object> a = new LinkedHashMap<>();
	a.put("accountType", type);
	a.put("balance", balance);
	a.put("proof", "PAN-123");
	a.put("nominee", nominee());
	return a;
    }

    protected AccountRef openAccount(Session s, String type, double balance) {
	HttpResult r = post("/account/create/" + s.userId(), s.token(), accountBody(type, balance));
	assertThat(r.status()).as("create account: %s", r.body()).isEqualTo(201);
	return latestAccount(s.userId());
    }

    protected AccountRef latestAccount(long userId) {
	return jdbc.queryForObject(
		"SELECT a.id, a.account_number, c.card_number FROM account a LEFT JOIN card c ON c.id = a.card_id "
			+ "WHERE a.user_id = ? ORDER BY a.id DESC LIMIT 1",
		(rs, i) -> new AccountRef(rs.getLong(1), rs.getLong(2), (Long) rs.getObject(3, Long.class)), userId);
    }

    protected int count(String table) {
	return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }
}
