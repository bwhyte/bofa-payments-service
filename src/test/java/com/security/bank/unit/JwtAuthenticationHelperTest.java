package com.security.bank.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import com.security.bank.entity.Role;
import com.security.bank.entity.User;
import com.security.bank.jwt.JwtAuthenticationHelper;
import com.security.bank.repository.UserRepository;
import com.security.bank.security.CustomUserDetailService;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.MalformedJwtException;

class JwtAuthenticationHelperTest {

    private final JwtAuthenticationHelper helper = new JwtAuthenticationHelper();

    private static User user(String username, String role) {
	User u = new User();
	u.setUsername(username);
	u.setRoles(new Role(null, role));
	return u;
    }

    @Test
    void generatedTokenRoundTrips() {
	String token = helper.generateToken(user("alice", "ROLE_CUSTOMER"));
	assertThat(helper.getUsernameFromToken(token)).isEqualTo("alice");
	assertThat(helper.isTokenExpired(token)).isFalse();
	Claims claims = helper.getClaimsFromToken(token);
	assertThat(claims.getExpiration().getTime() - claims.getIssuedAt().getTime()).isEqualTo(3_600_000L);
	assertThat(claims).containsOnlyKeys("sub", "iat", "exp");
    }

    @Test
    void malformedTokenThrows() {
	assertThatThrownBy(() -> helper.getUsernameFromToken("abc")).isInstanceOf(MalformedJwtException.class);
    }

    @Test
    void expiredTokenThrowsBeforeExpiryCheck() {
	String token = io.jsonwebtoken.Jwts.builder().setSubject("alice")
		.setExpiration(new java.util.Date(System.currentTimeMillis() - 1000))
		.signWith(new javax.crypto.spec.SecretKeySpec(
			((String) org.springframework.test.util.ReflectionTestUtils.getField(helper, "secret")).getBytes(),
			"HmacSHA512"), io.jsonwebtoken.SignatureAlgorithm.HS512)
		.compact();
	assertThatThrownBy(() -> helper.isTokenExpired(token)).isInstanceOf(ExpiredJwtException.class);
    }

    @Test
    void userAuthoritiesComeFromRoleName() {
	User u = user("bob", "ROLE_ADMIN");
	assertThat(u.getAuthorities()).extracting("authority").containsExactly("ROLE_ADMIN");
	assertThat(u.isEnabled()).isTrue();
	assertThat(u.isAccountNonExpired()).isTrue();
	assertThat(u.isAccountNonLocked()).isTrue();
	assertThat(u.isCredentialsNonExpired()).isTrue();
    }

    @Test
    void unknownUserLookupThrowsPlainRuntimeException() {
	UserRepository repo = Mockito.mock(UserRepository.class);
	Mockito.when(repo.findByUsername("ghost")).thenReturn(Optional.empty());
	assertThatThrownBy(() -> new CustomUserDetailService(repo).loadUserByUsername("ghost"))
		.isExactlyInstanceOf(RuntimeException.class).hasMessage("User Not Found");
    }
}
