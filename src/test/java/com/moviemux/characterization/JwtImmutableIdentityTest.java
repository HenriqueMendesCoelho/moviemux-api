package com.moviemux.characterization;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.moviemux.application.spring.security.JWTAuthorizationFilter;
import com.moviemux.application.spring.security.UserSS;
import com.moviemux.application.spring.security.util.JWTUtil;
import com.moviemux.user.domain.model.Role;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.servlet.FilterChain;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

class JwtImmutableIdentityTest {
  private static final UUID ORIGINAL_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
  private static final String EMAIL = "synthetic-reused-email@example.invalid";
  private JWTUtil jwt;
  private UserDetailsService users;
  private AuthenticationManager manager;

  @BeforeEach void setUp() {
    jwt = new JWTUtil();
    jwt.secret = "synthetic-identity-test-key-not-for-production-0123456789";
    jwt.expiration = 60000;
    users = mock(UserDetailsService.class);
    manager = mock(AuthenticationManager.class);
    SecurityContextHolder.clearContext();
  }

  @AfterEach void clearContext() { SecurityContextHolder.clearContext(); }

  @Test void standardIssuerTokensRetainTheirIdentityAndRemainCompatible() throws Exception {
    String token = token(ORIGINAL_ID, Set.of("ROLE_USER"));
    var identity = jwt.getVerifiedIdentity(token);
    assertThat(identity.userId()).isEqualTo(ORIGINAL_ID);
    assertThat(identity.username()).isEqualTo(EMAIL);
    assertThat(jwt.isTokenValid(token)).isTrue();
    assertThat(jwt.getUsername(token)).isEqualTo(EMAIL);
    var current = principal(ORIGINAL_ID, 0, Role.USER);
    when(users.loadUserByUsername(EMAIL)).thenReturn(current);
    assertThat(authenticate(token).getPrincipal()).isSameAs(current);
  }

  static Stream<Arguments> invalidIds() {
    return Stream.of(
        Arguments.of((Object) null), Arguments.of(""), Arguments.of("not-a-uuid"),
        Arguments.of("1-1-1-1-1"), Arguments.of("10000000-0000-0000-0000-000000000001 "),
        Arguments.of(" 10000000-0000-0000-0000-000000000001"), Arguments.of(123),
        Arguments.of(Map.of("uuid", ORIGINAL_ID.toString())));
  }

  @ParameterizedTest @MethodSource("invalidIds")
  void missingWrongTypeOrMalformedIdNeverLoadsAnAccount(Object id) throws Exception {
    String token = signedToken(id, EMAIL, new Date(System.currentTimeMillis() + 60000));
    assertThat(jwt.getVerifiedIdentity(token)).isNull();
    assertThat(jwt.isTokenValid(token)).isFalse();
    assertThat(jwt.getUsername(token)).isNull();
    assertThat(authenticate(token)).isNull();
    verifyNoInteractions(users);
  }

  @Test void missingSubjectExpiryAndBlankSubjectRemainInvalid() throws Exception {
    for (String token : new String[] {
        signedToken(ORIGINAL_ID.toString(), null, new Date(System.currentTimeMillis() + 60000)),
        signedToken(ORIGINAL_ID.toString(), " ", new Date(System.currentTimeMillis() + 60000)),
        signedToken(ORIGINAL_ID.toString(), EMAIL, null)}) {
      assertThat(jwt.getVerifiedIdentity(token)).isNull();
      assertThat(authenticate(token)).isNull();
    }
    verifyNoInteractions(users);
  }

  @Test void oldTokenCannotBecomeRecreatedAccountsIdentityAtTheSameEmail() throws Exception {
    String oldToken = token(ORIGINAL_ID, Set.of("ROLE_USER"));
    var original = principal(ORIGINAL_ID, 0, Role.USER);
    var replacement = principal(UUID.randomUUID(), 0, Role.ADM);
    when(users.loadUserByUsername(EMAIL)).thenReturn(original, replacement, replacement);
    assertThat(authenticate(oldToken).getPrincipal()).isSameAs(original);
    // Also clears any already populated context when the supplied token cannot be bound.
    assertThat(authenticate(oldToken)).isNull();
    String newToken = token(replacement.getUserid(), Set.of("ROLE_USER"));
    assertThat(authenticate(newToken).getPrincipal()).isSameAs(replacement);
  }

  @Test void removedAccountFailsClosedWithoutThrowingItsLookupException() throws Exception {
    when(users.loadUserByUsername(EMAIL)).thenThrow(new UsernameNotFoundException("private lookup detail"));
    assertThat(authenticate(token(ORIGINAL_ID, Set.of("ROLE_USER")))).isNull();
  }

  @Test void principalWithoutImmutableIdentityCannotAuthenticateByEmailAlone() throws Exception {
    when(users.loadUserByUsername(EMAIL)).thenReturn(User.withUsername(EMAIL).password("unused").roles("ADM").build());
    assertThat(authenticate(token(ORIGINAL_ID, Set.of("ROLE_USER")))).isNull();
  }

  @Test void principalWithMissingIdAlsoFailsClosed() throws Exception {
    when(users.loadUserByUsername(EMAIL)).thenReturn(principal(null, 0, Role.USER));
    assertThat(authenticate(token(ORIGINAL_ID, Set.of("ROLE_USER")))).isNull();
  }

  @Test void malformedTokenClearsPriorAuthenticationWithoutLoadingAccount() throws Exception {
    SecurityContextHolder.getContext().setAuthentication(
        new UsernamePasswordAuthenticationToken("unrelated", null, java.util.List.of()));
    assertThat(authenticate("broken.token")).isNull();
    verifyNoInteractions(users);
  }

  @Test void currentDatabaseRolesOverrideBothPromotedAndStaleAdminClaims() throws Exception {
    var ordinary = principal(ORIGINAL_ID, 0, Role.USER);
    var promoted = principal(ORIGINAL_ID, 0, Role.ADM);
    when(users.loadUserByUsername(EMAIL)).thenReturn(ordinary, promoted);
    assertThat(authenticate(token(ORIGINAL_ID, Set.of("ROLE_ADM"))).getAuthorities())
        .extracting("authority").containsExactly("ROLE_USER");
    assertThat(authenticate(token(ORIGINAL_ID, Set.of("ROLE_USER"))).getAuthorities())
        .extracting("authority").containsExactly("ROLE_ADM");
  }

  @Test void currentLockedStateRevokesPreviouslyAuthenticatedTokenImmediately() throws Exception {
    String token = token(ORIGINAL_ID, Set.of("ROLE_USER"));
    var active = principal(ORIGINAL_ID, 9, Role.USER);
    var locked = principal(ORIGINAL_ID, 10, Role.USER);
    when(users.loadUserByUsername(EMAIL)).thenReturn(active, locked, active);
    assertThat(authenticate(token).getPrincipal()).isSameAs(active);
    // Prior populated context must also be cleared when the same token becomes disallowed.
    assertThat(authenticate(token)).isNull();
    assertThat(authenticate(token).getPrincipal()).isSameAs(active);
  }

  @ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(strings = {"disabled", "locked", "accountExpired", "credentialsExpired"})
  void everyCurrentInvalidAccountFlagFailsClosedEvenForAdmin(String invalidState) throws Exception {
    var current = spy(principal(ORIGINAL_ID, 0, Role.ADM));
    switch (invalidState) {
      case "disabled" -> doReturn(false).when(current).isEnabled();
      case "locked" -> doReturn(false).when(current).isAccountNonLocked();
      case "accountExpired" -> doReturn(false).when(current).isAccountNonExpired();
      case "credentialsExpired" -> doReturn(false).when(current).isCredentialsNonExpired();
      default -> throw new AssertionError("Unrecognized state");
    }
    when(users.loadUserByUsername(EMAIL)).thenReturn(current);
    SecurityContextHolder.getContext().setAuthentication(
        new UsernamePasswordAuthenticationToken("prior", null, java.util.List.of()));
    assertThat(authenticate(token(ORIGINAL_ID, Set.of("ROLE_ADM")))).isNull();
  }

  private String token(UUID id, Set<String> roles) {
    return jwt.generateToken(id, EMAIL, "Synthetic identity", roles);
  }

  private String signedToken(Object id, String subject, Date expiresAt) {
    return Jwts.builder().subject(subject).expiration(expiresAt).claim("id", id)
        .signWith(Keys.hmacShaKeyFor(jwt.secret.getBytes(StandardCharsets.UTF_8))).compact();
  }

  private UserSS principal(UUID id, int failedAttempts, Role role) {
    return new UserSS(id, "Synthetic identity", EMAIL, "unused", failedAttempts, Set.of(role));
  }

  private Authentication authenticate(String token) throws Exception {
    var request = new MockHttpServletRequest("GET", "/movie");
    request.addHeader("Authorization", "Bearer " + token);
    var response = new MockHttpServletResponse();
    var chain = mock(FilterChain.class);
    new JWTAuthorizationFilter(manager, jwt, users).doFilter(request, response, chain);
    verify(chain).doFilter(request, response);
    return SecurityContextHolder.getContext().getAuthentication();
  }
}
