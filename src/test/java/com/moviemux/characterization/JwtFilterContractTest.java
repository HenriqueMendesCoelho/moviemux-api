package com.moviemux.characterization;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.moviemux.application.spring.security.JWTAuthenticationFilter;
import com.moviemux.application.spring.security.JWTAuthorizationFilter;
import com.moviemux.application.spring.security.UserSS;
import com.moviemux.application.spring.security.util.JWTUtil;
import com.moviemux.user.domain.model.Role;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.*;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetailsService;

import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class JwtFilterContractTest {
    private JWTUtil jwt;
    private UserDetailsService users;
    private AuthenticationManager manager;
    private final UUID id = UUID.fromString("00000000-0000-0000-0000-000000000001");
    @BeforeEach void setup() {
        jwt = new JWTUtil();
        jwt.secret = "synthetic-test-signing-key-never-use-in-production-0123456789";
        jwt.expiration = 60000;
        users = mock(UserDetailsService.class);
        manager = mock(AuthenticationManager.class);
        SecurityContextHolder.clearContext();
    }
    @AfterEach void clearContext() { SecurityContextHolder.clearContext(); }
    private String token() { return jwt.generateToken(id, "synthetic@example.test", "Synthetic", Set.of("ROLE_USER")); }
    @Test void validSignedTokenLoadsCurrentAuthoritiesFromUserService() throws Exception {
        var principal = new UserSS(id, "Synthetic", "synthetic@example.test", "not-a-real-password", 0, Set.of(Role.USER));
        when(users.loadUserByUsername("synthetic@example.test")).thenReturn(principal);
        var request = new MockHttpServletRequest("GET", "/movie");
        request.addHeader("Authorization", "Bearer " + token());
        FilterChain chain = mock(FilterChain.class);
        var response = new MockHttpServletResponse();
        new JWTAuthorizationFilter(manager, jwt, users).doFilter(request, response, chain);
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        assertThat(authentication.getPrincipal()).isSameAs(principal);
        assertThat(authentication.getAuthorities()).extracting("authority").containsExactly("ROLE_USER");
        verify(chain).doFilter(request, response);
    }
    @Test void malformedTokenNeverLoadsPrincipal() throws Exception {
        var request = new MockHttpServletRequest("GET", "/movie");
        request.addHeader("Authorization", "Bearer broken.token");
        new JWTAuthorizationFilter(manager, jwt, users).doFilter(request, new MockHttpServletResponse(), mock(FilterChain.class));
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verifyNoInteractions(users);
    }
    @Test void expiredTokenIsRejected() {
        jwt.expiration = -60000;
        assertThat(jwt.isTokenValid(token())).isFalse();
    }
    @Test void tokenSignedByAnotherKeyIsRejected() {
        String token = token();
        jwt.secret = "different-synthetic-test-signing-key-not-real-0123456789";
        assertThat(jwt.isTokenValid(token)).isFalse();
    }
    @Test void loginReadsJsonEmailAndPassword() {
        var request = new MockHttpServletRequest("POST", "/login");
        request.setContent("{\"email\":\"synthetic@example.test\",\"password\":\"Synthetic1!\"}".getBytes(StandardCharsets.UTF_8));
        var auth = new UsernamePasswordAuthenticationToken("synthetic@example.test", "Synthetic1!");
        when(manager.authenticate(any())).thenReturn(auth);
        assertThat(new JWTAuthenticationFilter(manager, jwt).attemptAuthentication(request, new MockHttpServletResponse())).isSameAs(auth);
        verify(manager).authenticate(argThat(value -> value.getPrincipal().equals("synthetic@example.test")
                && value.getCredentials().equals("Synthetic1!")));
    }
    @Test void unsuccessfulLoginHasStableUnauthorizedEnvelope() throws Exception {
        var request = new MockHttpServletRequest("POST", "/login");
        request.setServletPath("/login");
        request.setContent("{\"email\":\"synthetic@example.test\",\"password\":\"wrong\"}".getBytes(StandardCharsets.UTF_8));
        when(manager.authenticate(any())).thenThrow(new BadCredentialsException("private reason"));
        var response = new MockHttpServletResponse();
        new JWTAuthenticationFilter(manager, jwt).doFilter(request, response, mock(FilterChain.class));
        assertThat(response.getStatus()).isEqualTo(401);
        var json = new ObjectMapper().readTree(response.getContentAsString());
        assertThat(json.get("status").asInt()).isEqualTo(401);
        assertThat(json.get("message").asText()).isEqualTo("Email or password are incorrect.");
        assertThat(json.get("path").asText()).isEqualTo("/login");
        assertThat(json.get("timestamp").isNumber()).isTrue();
    }
    @Test void accountLocksAtTenFailedAttempts() {
        assertThat(new UserSS(id, "Synthetic", "synthetic@example.test", "test", 9, Set.of(Role.USER)).isAccountNonLocked()).isTrue();
        assertThat(new UserSS(id, "Synthetic", "synthetic@example.test", "test", 10, Set.of(Role.USER)).isAccountNonLocked()).isFalse();
    }
}
