package com.moviemux.user.application.spring.controller;

import com.moviemux.core.adapter.controller.dto.UserTokenDto;
import com.moviemux.core.adapter.util.CredentialUtil;
import com.moviemux.user.adapter.controller.UserController;
import com.moviemux.user.adapter.controller.dto.UserRequestDto;
import com.moviemux.user.adapter.controller.dto.UserResponseAdmDto;
import com.moviemux.user.adapter.controller.dto.UserResponseDto;
import com.moviemux.user.usecase.exception.UserNotAuthorizedException;
import com.moviemux.user.usecase.exception.UserNotFoundException;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/restricted/user")
@RequiredArgsConstructor
public class UserRestrictedSpringController {

	private final UserController controller;

	@GetMapping
	@PreAuthorize("hasRole('ADM')")
	public ResponseEntity<List<UserResponseAdmDto>> getAllUsers() {
		try {
			List<UserResponseAdmDto> response = controller.getAllUsers();
			return ResponseEntity.ok(response);
		} catch (UserNotFoundException e) {
			return ResponseEntity.noContent().build();
		}
	}

	@PatchMapping("/{id}")
	@PreAuthorize("hasRole('ADM')")
	public ResponseEntity<?> updateUser(@PathVariable UUID id, @RequestBody @Valid UserRequestDto request,
			@RequestHeader("Authorization") String token) {

		try {
			UserTokenDto user = CredentialUtil.getUserFromToken(token);
			UserResponseDto response = controller.update(request, id, user);
			return ResponseEntity.ok(response);
		} catch (UserNotFoundException | UserNotAuthorizedException e) {
			return ResponseEntity.badRequest().body(Map.of("error", true, "status", 400, "message", e.getMessage()));
		}
	}

	@GetMapping("/{email}")
	@PreAuthorize("hasRole('ADM')")
	public ResponseEntity<?> getUserAdm(@PathVariable String email, @RequestHeader("Authorization") String token) {
		try {
			UserTokenDto user = CredentialUtil.getUserFromToken(token);
			UserResponseAdmDto response = controller.getUserByEmailAdm(user, email);
			return ResponseEntity.ok(response);
		} catch (UserNotFoundException e) {
			return ResponseEntity.noContent().build();
		} catch (UserNotAuthorizedException e) {
			return ResponseEntity.badRequest().body(Map.of("error", true, "status", 400, "message", e.getMessage()));
		}
	}

	@PatchMapping("/{id}/promote")
	@PreAuthorize("hasRole('ADM')")
	public ResponseEntity<?> promoteUser(@PathVariable UUID id) {

		try {
			UserResponseAdmDto response = controller.promoteUserToAdmin(id);
			return ResponseEntity.ok(response);
		} catch (UserNotFoundException e) {
			return ResponseEntity.badRequest().body(Map.of("error", true, "status", 400, "message", e.getMessage()));
		}
	}

	@PatchMapping("/{id}/demote")
	@PreAuthorize("hasRole('ADM')")
	public ResponseEntity<?> demoteUser(@PathVariable UUID id) {

		try {
			UserResponseAdmDto response = controller.demoteUserToAdmin(id);
			return ResponseEntity.ok(response);
		} catch (UserNotFoundException e) {
			return ResponseEntity.badRequest().body(Map.of("error", true, "status", 400, "message", e.getMessage()));
		}
	}

	@PatchMapping("/{id}/block")
	@PreAuthorize("hasRole('ADM')")
	public ResponseEntity<?> blockUser(@PathVariable UUID id) {

		try {
			UserResponseAdmDto response = controller.blockUser(id);
			return ResponseEntity.ok(response);
		} catch (UserNotFoundException e) {
			return ResponseEntity.badRequest().body(Map.of("error", true, "status", 400, "message", e.getMessage()));
		}
	}

	@PostMapping("/{id}")
	@PreAuthorize("hasRole('ADM')")
	public ResponseEntity<?> blockUserAdm(@PathVariable UUID id) {
		controller.delete(id);
		return ResponseEntity.ok().build();
	}

	@DeleteMapping("/{id}")
	@PreAuthorize("hasRole('ADM')")
	public ResponseEntity<?> deleteUser(@PathVariable UUID id) {
		controller.delete(id);
		return ResponseEntity.ok().build();
	}
}
