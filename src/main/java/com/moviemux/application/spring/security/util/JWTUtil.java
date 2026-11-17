package com.moviemux.application.spring.security.util;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.Set;
import java.util.UUID;

@Slf4j
@Component
public class JWTUtil {

	private static final String AUDIENCE = "https://www.moviemux.com/api";

	@Value("${jwt.secret}")
	public String secret;

	@Value("${jwt.expiration}")
	public long expiration;

	private SecretKey getSigningKey() {
		byte[] keyBytes = secret.getBytes(StandardCharsets.UTF_8);
		return Keys.hmacShaKeyFor(keyBytes);
	}

	public String generateToken(UUID userId, String username, String name, Set<String> roles) {
		return Jwts.builder()
				.subject(username)
				.expiration(new Date(System.currentTimeMillis() + expiration))
				.signWith(getSigningKey())
				.audience()
				.add(AUDIENCE)
				.and()
				.claim("id", userId)
				.claim("name", name)
				.claim("roles", roles.stream().map(s -> s.split("_")[1]).toList())
				.compact();
	}

	/** Signature, expiry and immutable subject identity; roles are deliberately not returned. */
	public VerifiedTokenIdentity getVerifiedIdentity(String token) {
		Claims claims = getClaims(token);
		if (claims == null) return null;
		try {
			String username = claims.getSubject();
			Date expiresAt = claims.getExpiration();
			Object rawId = claims.get("id");
			if (StringUtils.isBlank(username) || expiresAt == null || !new Date().before(expiresAt)
					|| !(rawId instanceof String idText)) return null;
			UUID userId = UUID.fromString(idText);
			// UUID.fromString also accepts shortened groups; require the issuer's canonical shape.
			if (!userId.toString().equalsIgnoreCase(idText)) return null;
			return new VerifiedTokenIdentity(userId, username);
		} catch (RuntimeException invalidIdentity) {
			return null;
		}
	}

	public boolean isTokenValid(String token) {
		return getVerifiedIdentity(token) != null;
	}

	public String getUsername(String token) {
		VerifiedTokenIdentity identity = getVerifiedIdentity(token);
		return identity == null ? null : identity.username();
	}

	public record VerifiedTokenIdentity(UUID userId, String username) {}

	private Claims getClaims(String token) {
		try {
			return Jwts.parser().verifyWith(getSigningKey()).build().parseSignedClaims(token).getPayload();
		} catch (Exception e) {
			log.error("Error to parse token", e);
			return null;
		}
	}

}
