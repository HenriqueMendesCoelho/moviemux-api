package com.moviemux.characterization;

import com.moviemux.application.spring.configuration.SecurityConfig;
import com.moviemux.application.spring.security.util.JWTUtil;
import com.moviemux.user.adapter.controller.UserController;
import com.moviemux.user.application.spring.controller.UserInviteRestrictSpringController;
import org.junit.jupiter.api.*;
import org.springframework.context.annotation.*;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import java.util.Set;
import java.util.UUID;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Real application filter chain with synthetic principals, no application/DB startup. */
class SecurityChainContractTest {
    private AnnotationConfigWebApplicationContext context;
    private MockMvc mvc;
    @org.springframework.context.annotation.Configuration @EnableWebMvc @Import(SecurityConfig.class)
    static class Configuration {
        @Bean JWTUtil jwtUtil() {
            var jwt = new JWTUtil();
            jwt.secret = "synthetic-security-chain-key-never-use-for-production-0123456789";
            jwt.expiration = 60000;
            return jwt;
        }
        @Bean UserDetailsService userDetailsService() {
            return username -> User.withUsername(username).password("unused").roles(username.startsWith("admin") ? "ADM" : "USER").build();
        }
        @Bean UserController userController() { return mock(UserController.class); }
        @Bean UserInviteRestrictSpringController invitations(UserController users) { return new UserInviteRestrictSpringController(users); }
        @Bean ProbeController probes() { return new ProbeController(); }
    }
    @RestController static class ProbeController {
        @GetMapping({"/public/probe", "/movie/probe", "/actuator/probe"}) String read() { return "ok"; }
    }
    @BeforeEach void setup() {
        context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext());
        org.springframework.test.context.support.TestPropertySourceUtils.addInlinedPropertiesToEnvironment(context,
                "jwt.secret=synthetic-security-chain-key-never-use-for-production-0123456789", "jwt.expiration=60000");
        context.register(Configuration.class);
        context.refresh();
        mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(context.getBean(FilterChainProxy.class)).build();
    }
    @AfterEach void close() { context.close(); }
    private String token(String user) {
        return "Bearer " + context.getBean(JWTUtil.class).generateToken(UUID.fromString("00000000-0000-0000-0000-000000000001"),
                user, "Synthetic", Set.of("ROLE_USER"));
    }
    @Test void publicRoutesAllowAnonymous() throws Exception { mvc.perform(get("/public/probe")).andExpect(status().isOk()); }
    @Test void privateRoutesRejectAnonymous() throws Exception { mvc.perform(get("/movie/probe")).andExpect(status().isForbidden()); }
    @Test void privateRoutesAcceptValidUserToken() throws Exception {
        mvc.perform(get("/movie/probe").header("Authorization", token("user@example.test"))).andExpect(status().isOk());
    }
    @Test void actuatorGetRejectsUserRole() throws Exception {
        mvc.perform(get("/actuator/probe").header("Authorization", token("user@example.test"))).andExpect(status().isForbidden());
    }
    @Test void actuatorGetAcceptsCurrentAdminAuthorityEvenWhenClaimSaysUser() throws Exception {
        mvc.perform(get("/actuator/probe").header("Authorization", token("admin@example.test"))).andExpect(status().isOk());
    }
    @Test void actualInviteControllerRejectsUserBeforeDelegate() throws Exception {
        mvc.perform(get("/restricted/user/invite").header("Authorization", token("user@example.test"))).andExpect(status().isForbidden());
        verifyNoInteractions(context.getBean(UserController.class));
    }
    @Test void actualInviteControllerAllowsAdmin() throws Exception {
        mvc.perform(get("/restricted/user/invite").header("Authorization", token("admin@example.test"))).andExpect(status().isOk());
        verify(context.getBean(UserController.class)).getAllInvites();
    }
}
