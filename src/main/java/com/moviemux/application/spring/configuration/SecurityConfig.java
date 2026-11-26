package com.moviemux.application.spring.configuration;

import com.moviemux.application.spring.security.JWTAuthenticationFilter;
import com.moviemux.application.spring.security.JWTAuthorizationFilter;
import com.moviemux.application.spring.security.util.JWTUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.builders.AuthenticationManagerBuilder;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.Http403ForbiddenEntryPoint;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.AnyRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;

import static org.springframework.security.config.Customizer.withDefaults;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

	private static final String[] ADMIN_REGISTRATION={"/restricted/registration-mode", "/restricted/registration-waitlist", "/restricted/registration-waitlist/**"};
	private static final String MANUAL_CATALOG = "/restricted/movies/catalog/manual";
	private static final String[] PUBLIC_MATCHERS = { "/public/**" };
	private static final String[] PUBLIC_RANKING_GET = {
			"/public/movies/collections/{rankingId}", "/public/movies/collections/{rankingId}/entries",
			"/public/rankings/{rankingId}", "/public/rankings/{rankingId}/entries"
	};
	private final UserDetailsService userDetailsService;
	private final AuthenticationConfiguration authenticationConfiguration;
	private final JWTUtil jwtUtil;

	public SecurityConfig(UserDetailsService userDetailsService,
			AuthenticationConfiguration authenticationConfiguration, JWTUtil jwtUtil) {
		this.userDetailsService = userDetailsService;
		this.authenticationConfiguration = authenticationConfiguration;
		this.jwtUtil = jwtUtil;
	}

	@Autowired
	protected void configure(AuthenticationManagerBuilder auth) throws Exception {
		auth.userDetailsService(userDetailsService).passwordEncoder(new BCryptPasswordEncoder());
	}

	@Bean
	AuthenticationManager authenticationManager(AuthenticationConfiguration authenticationConfiguration)
			throws Exception {
		return authenticationConfiguration.getAuthenticationManager();
	}

	@Bean
	BCryptPasswordEncoder bCryptPasswordEncoder() {
		return new BCryptPasswordEncoder();
	}

	@Bean
	@Order(1)
	SecurityFilterChain anonymousRankingProjection(HttpSecurity http) throws Exception {
		http.securityMatcher("/public/movies/collections", "/public/movies/collections/**",
				"/public/rankings", "/public/rankings/**");
		// These are anonymous regardless of cookies, Authorization or caller headers. Do not
		// install the account JWT filters or restore / save a user session for this namespace.
		http.csrf(AbstractHttpConfigurer::disable);
		http.cors(AbstractHttpConfigurer::disable);
		http.securityContext(AbstractHttpConfigurer::disable);
		http.requestCache(AbstractHttpConfigurer::disable);
		http.logout(AbstractHttpConfigurer::disable);
		http.sessionManagement(management -> management.sessionCreationPolicy(SessionCreationPolicy.STATELESS));
		http.authorizeHttpRequests(authz -> authz
				.requestMatchers(HttpMethod.GET, PUBLIC_RANKING_GET).permitAll()
				.anyRequest().denyAll());
		http.exceptionHandling(errors -> errors
				.authenticationEntryPoint((request, response, failure) -> unavailablePublicRanking(response))
				.accessDeniedHandler((request, response, failure) -> unavailablePublicRanking(response)));
		return http.build();
	}

	private void unavailablePublicRanking(HttpServletResponse response) throws IOException {
		response.setStatus(HttpServletResponse.SC_NOT_FOUND);
		response.setHeader("Cache-Control", "no-store");
		response.setContentType("application/json");
		response.getWriter().write("{\"code\":\"ranking.not_found\"}");
	}

	@Bean
	@Order(2)
	SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
		http.cors(withDefaults());
		http.csrf(AbstractHttpConfigurer::disable);
		http.authorizeHttpRequests(authz -> authz.requestMatchers(PUBLIC_MATCHERS).permitAll()
				.requestMatchers(MANUAL_CATALOG).hasAuthority("ROLE_ADM")
				.requestMatchers(ADMIN_REGISTRATION).hasAuthority("ROLE_ADM")
				.requestMatchers(HttpMethod.GET, "/actuator/**")
				.hasAuthority("ROLE_ADM")
				.anyRequest()
				.authenticated());
		http.exceptionHandling(errors -> errors
				.defaultAuthenticationEntryPointFor((request, response, failure) -> {
					response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
					response.setHeader("Cache-Control", "no-store");
				}, new OrRequestMatcher(
                        PathPatternRequestMatcher.withDefaults().matcher(MANUAL_CATALOG),
                        PathPatternRequestMatcher.withDefaults().matcher("/restricted/registration-mode"),
                        PathPatternRequestMatcher.withDefaults().matcher("/restricted/registration-waitlist"),
                        PathPatternRequestMatcher.withDefaults().matcher("/restricted/registration-waitlist/**")))
				.defaultAuthenticationEntryPointFor(new Http403ForbiddenEntryPoint(), AnyRequestMatcher.INSTANCE));
		http.addFilter(jwtAuthorizationFilter());
		http.addFilter(new JWTAuthorizationFilter(authenticationManager(authenticationConfiguration), jwtUtil,
				userDetailsService));
		http.sessionManagement(management -> management.sessionCreationPolicy(SessionCreationPolicy.STATELESS));

		return http.build();
	}

	private JWTAuthenticationFilter jwtAuthorizationFilter() throws Exception {
		return new JWTAuthenticationFilter(authenticationManager(authenticationConfiguration), jwtUtil);
	}

}
