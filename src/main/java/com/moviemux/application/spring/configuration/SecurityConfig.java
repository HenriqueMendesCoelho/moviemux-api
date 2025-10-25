package com.moviemux.application.spring.configuration;

import com.moviemux.application.spring.security.JWTAuthenticationFilter;
import com.moviemux.application.spring.security.JWTAuthorizationFilter;
import com.moviemux.application.spring.security.util.JWTUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
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

import static org.springframework.security.config.Customizer.withDefaults;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

	private static final String[] PUBLIC_MATCHERS = { "/public/**" };
	private final UserDetailsService userDetailsService;
	private final AuthenticationConfiguration authenticationConfiguration;
	private final JWTUtil jwtUtil;

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
	SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
		http.cors(withDefaults());
		http.csrf(AbstractHttpConfigurer::disable);
		http.authorizeHttpRequests(authz -> authz.requestMatchers(PUBLIC_MATCHERS)
				.permitAll()
				.requestMatchers(HttpMethod.GET, "/actuator/**")
				.hasAuthority("ROLE_ADM")
				.anyRequest()
				.authenticated());
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
