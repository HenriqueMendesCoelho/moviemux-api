package com.moviemux.application.spring.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.moviemux.adapter.core.controller.dto.UserLoginDto;
import com.moviemux.application.spring.security.dto.LoginResponseDto;
import com.moviemux.application.spring.security.util.JWTUtil;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Date;
import java.util.Set;
import java.util.UUID;

@Slf4j
public class JWTAuthenticationFilter extends UsernamePasswordAuthenticationFilter {

	private final AuthenticationManager authenticationManager;

	private final JWTUtil jwtUtil;

	public JWTAuthenticationFilter(AuthenticationManager authenticationManager, JWTUtil jwtUtil) {
		setAuthenticationFailureHandler(new JWTAuthenticationFailureHandler());
		this.authenticationManager = authenticationManager;
		this.jwtUtil = jwtUtil;
	}

	@Override
	public Authentication attemptAuthentication(HttpServletRequest request, HttpServletResponse response)
			throws AuthenticationException {

		try {
			UserLoginDto creds = new ObjectMapper().readValue(request.getInputStream(), UserLoginDto.class);

			UsernamePasswordAuthenticationToken authToken = new UsernamePasswordAuthenticationToken(creds.getEmail(),
					creds.getPassword(), new ArrayList<>());

			Authentication auth = authenticationManager.authenticate(authToken);

			logRequest(request, response);

			return auth;
		} catch (IOException e) {
			throw new RuntimeException(e);
		}

	}

	@Override
	protected void successfulAuthentication(HttpServletRequest request, HttpServletResponse response, FilterChain chain,
			Authentication auth) throws IOException, ServletException {

		String username = ((UserSS) auth.getPrincipal()).getUsername();
		String name = ((UserSS) auth.getPrincipal()).getName();
		UUID userId = ((UserSS) auth.getPrincipal()).getUserid();
		Set<String> roles = ((UserSS) auth.getPrincipal()).getRoles();
		String token = jwtUtil.generateToken(userId, username, name, roles);

		response.setContentType("application/json");
		response.getWriter().append(new LoginResponseDto(token, jwtUtil.expiration).toJson());
	}

	private void logRequest(HttpServletRequest request, HttpServletResponse response) {
		log.info("[" + response.getStatus() + "]" + "[" + request.getMethod() + "]" + request.getRequestURI() + "[from:"
				+ request.getHeader("x-forwarded-for") + "]");
	}

	private class JWTAuthenticationFailureHandler implements AuthenticationFailureHandler {
		@Override
		public void onAuthenticationFailure(HttpServletRequest request, HttpServletResponse response,
				AuthenticationException exception) throws IOException, ServletException {

			response.setStatus(401);
			response.setContentType("application/json");

			response.getWriter().append(jsonNormal());

			logRequest(request, response);
		}

		private String jsonNormal() {
			long date = new Date().getTime();
			return "{\"timestamp\": " + date + ", " + "\"status\": 401, " + "\"error\": \"Not authorized\", "
					+ "\"message\": \"Email or password are incorrect.\", " + "\"path\": \"/login\"}";
		}
	}
}
