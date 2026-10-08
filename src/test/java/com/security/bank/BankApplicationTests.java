package com.security.bank;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import com.security.bank.dto.JwtRequest;
import com.security.bank.dto.JwtResponse;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class BankApplicationTests {

    @Autowired
    TestRestTemplate rest;

    @Test
    void registerThenLoginReturnsJwt() {
	Map<String, Object> user = Map.of("username", "john", "password", "john123", "name", "John Doe",
		"email", "john@example.com", "userType", "CUSTOMER");

	ResponseEntity<Void> registered = rest.postForEntity("/user/register", user, Void.class);
	assertThat(registered.getStatusCode()).isEqualTo(HttpStatus.CREATED);

	ResponseEntity<JwtResponse> login = rest.postForEntity("/user/login", new JwtRequest("john", "john123"),
		JwtResponse.class);
	assertThat(login.getStatusCode()).isEqualTo(HttpStatus.OK);
	assertThat(login.getBody().getJwtToken()).isNotBlank();
    }
}
