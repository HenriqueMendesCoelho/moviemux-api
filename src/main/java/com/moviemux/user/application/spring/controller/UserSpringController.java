package com.moviemux.user.application.spring.controller;

import com.moviemux.core.adapter.controller.dto.UserTokenDto;
import com.moviemux.core.adapter.util.CredentialUtil;
import com.moviemux.user.adapter.controller.UserController;
import com.moviemux.user.adapter.controller.dto.UserResponseDto;
import com.moviemux.user.usecase.exception.UserNotAuthorizedException;
import com.moviemux.user.usecase.exception.UserNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/user")
@RequiredArgsConstructor
public class UserSpringController {

	private final UserController controller;

	@GetMapping
	public ResponseEntity<?> getUser(@RequestHeader("Authorization") String token) {
		try {
			UserTokenDto user = CredentialUtil.getUserFromToken(token);
			UserResponseDto response = controller.getUserByEmail(user, user.getLogin());
			return ResponseEntity.ok(response);
		} catch (UserNotFoundException e) {
			return ResponseEntity.noContent().build();
		} catch (UserNotAuthorizedException e) {
			return ResponseEntity.badRequest().body(Map.of("error", true, "status", 400, "message", e.getMessage()));
		}
	}

	@PatchMapping(path = "/update", consumes = { MediaType.APPLICATION_FORM_URLENCODED_VALUE })
	public ResponseEntity<?> updateUserProfile(@RequestParam(required = true) String name,
			@RequestParam(required = true) String email, @RequestParam(required = true) boolean notify,
			@RequestHeader("Authorization") String token) {
		try {
			UserTokenDto user = CredentialUtil.getUserFromToken(token);
			UserResponseDto response = controller.updateUserProfile(user.getId(), name, email, notify);
			return ResponseEntity.ok(response);
		} catch (UserNotFoundException e) {
			return ResponseEntity.noContent().build();
		} catch (Exception e) {
			log.error(e.getMessage());
			return ResponseEntity.noContent().build();
		}
	}

	@PatchMapping(path = "/p/update", consumes = { MediaType.APPLICATION_FORM_URLENCODED_VALUE })
	public ResponseEntity<?> updateUserPassword(@RequestParam(required = true) String password,
			@RequestParam(required = true) String newPassword, @RequestHeader("Authorization") String token) {

		try {
			UserTokenDto user = CredentialUtil.getUserFromToken(token);
			UserResponseDto response = controller.updateUserPassoword(user.getId(), password, newPassword);
			return ResponseEntity.ok(response);
		} catch (UserNotFoundException e) {
			return ResponseEntity.noContent().build();
		} catch (UserNotAuthorizedException e) {
			return ResponseEntity.badRequest().body(Map.of("error", true, "status", 400, "message", e.getMessage()));
		}
	}
}
