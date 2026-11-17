package com.moviemux.application.spring.security;

import com.moviemux.application.spring.security.util.JWTUtil;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter;

import java.io.IOException;

@Slf4j
public class JWTAuthorizationFilter extends BasicAuthenticationFilter {

	private final JWTUtil jwtUtil;

	private final UserDetailsService userDetailsService;

	public JWTAuthorizationFilter(AuthenticationManager authenticationManager, JWTUtil jwtUtil,
			UserDetailsService userDetailsService) {
		super(authenticationManager);
		this.jwtUtil = jwtUtil;
		this.userDetailsService = userDetailsService;
	}

	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws IOException, ServletException {
		String header = request.getHeader("Authorization");

		if (header != null && header.startsWith("Bearer ")) {
			UsernamePasswordAuthenticationToken auth = getAuthentication(header.substring(7));
			if (auth != null) {
				SecurityContextHolder.getContext().setAuthentication(auth);
			} else {
				SecurityContextHolder.clearContext();
			}
		}

		chain.doFilter(request, response);
		logRequest(request, response);
	}

	private UsernamePasswordAuthenticationToken getAuthentication(String token) {
		var identity = jwtUtil.getVerifiedIdentity(token);
		if (identity == null) return null;
		try {
			var current = userDetailsService.loadUserByUsername(identity.username());
			// Email can be reused after deletion. It is a lookup key, not lasting identity.
			if (!(current instanceof UserSS user) || !identity.userId().equals(user.getUserid())) {
				return null;
			}
			return new UsernamePasswordAuthenticationToken(user, null, user.getAuthorities());
		} catch (UsernameNotFoundException removedAccount) {
			return null;
		}
	}

	private void logRequest(HttpServletRequest request, HttpServletResponse response) {
		log.info("[" + response.getStatus() + "]" + "[" + request.getMethod() + "]" + request.getRequestURI() + "[from:"
				+ request.getHeader("x-forwarded-for") + "]");
	}

}
