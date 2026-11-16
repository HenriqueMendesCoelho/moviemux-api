package com.moviemux.composition;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.moviemux.application.spring.configuration.SecurityConfig;
import com.moviemux.application.spring.security.UserSS;
import com.moviemux.application.spring.security.util.JWTUtil;
import com.moviemux.user.domain.model.Role;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.context.support.TestPropertySourceUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

class SecurityConstructorWiringTest {
  private AnnotationConfigWebApplicationContext context;
  private MockMvc mvc;

  @Configuration
  @EnableWebMvc
  @Import(SecurityConfig.class)
  static class TestConfiguration {
    @Bean
    JWTUtil jwtUtil() {
      return new JWTUtil();
    }

    @Bean
    UserDetailsService userDetailsService() {
      String password = new BCryptPasswordEncoder().encode("Synthetic1!");
      return username ->
          new UserSS(
              UUID.fromString("00000000-0000-0000-0000-000000000001"),
              "Synthetic User",
              username,
              password,
              0,
              Set.of(Role.USER));
    }
  }

  @BeforeEach
  void setUp() {
    context = new AnnotationConfigWebApplicationContext();
    context.setServletContext(new MockServletContext());
    TestPropertySourceUtils.addInlinedPropertiesToEnvironment(
        context,
        "jwt.secret=synthetic-wiring-test-signing-key-never-use-in-production-0123456789",
        "jwt.expiration=60000");
    context.register(TestConfiguration.class);
    context.refresh();
    mvc =
        MockMvcBuilders.webAppContextSetup(context)
            .addFilters(context.getBean(FilterChainProxy.class))
            .build();
  }

  @AfterEach
  void tearDown() {
    if (context != null) context.close();
  }

  @Test
  void loginStillAuthenticatesBcryptAndReturnsASignedToken() throws Exception {
    var result =
        mvc.perform(
                post("/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {"email":"synthetic@example.test","password":"Synthetic1!"}
                        """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.access_token").isNotEmpty())
            .andExpect(jsonPath("$.expires").isNotEmpty())
            .andReturn();
    String token =
        new ObjectMapper()
            .readTree(result.getResponse().getContentAsString())
            .get("access_token")
            .asText();
    assertThat(context.getBean(JWTUtil.class).isTokenValid(token)).isTrue();
    assertThat(context.getBean(JWTUtil.class).getUsername(token))
        .isEqualTo("synthetic@example.test");
  }

  @Test
  void incorrectPasswordKeepsTheExistingUnauthorizedEnvelope() throws Exception {
    mvc.perform(
            post("/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"email":"synthetic@example.test","password":"wrong"}
                    """))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.status").value(401))
        .andExpect(jsonPath("$.error").value("Not authorized"))
        .andExpect(jsonPath("$.message").value("Email or password are incorrect."))
        .andExpect(jsonPath("$.path").value("/login"));
  }
}
