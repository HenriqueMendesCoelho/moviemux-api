package com.moviemux.board.application.spring.controller;

import java.util.Map;
import java.util.UUID;

import com.moviemux.board.usecase.exception.BoardMovieNotFoundException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.moviemux.adapter.core.controller.dto.UserTokenDto;
import com.moviemux.adapter.util.CredentialUtil;
import com.moviemux.board.adapter.controller.BoardController;
import com.moviemux.board.adapter.controller.dto.BoardMovieRatingRequestDto;
import com.moviemux.board.adapter.controller.dto.BoardMovieRatingResponseDto;
import com.moviemux.board.adapter.controller.dto.BoardMovieResponseDto;
import com.moviemux.board.adapter.controller.dto.BoardMemberResponseDto;
import com.moviemux.board.adapter.controller.dto.BoardPermissionRequestDto;
import com.moviemux.board.adapter.controller.dto.BoardPermissionResponseDto;
import com.moviemux.board.adapter.controller.dto.BoardRequestDto;
import com.moviemux.board.adapter.controller.dto.BoardResponseDto;
import com.moviemux.board.usecase.exception.BoardMemberNotFoundException;
import com.moviemux.board.usecase.exception.BoardNotFoundException;
import com.moviemux.board.usecase.exception.BoardNotAuthorizedException;
import com.moviemux.board.usecase.exception.DuplicateBoardMemberException;
import com.moviemux.board.usecase.exception.DuplicateBoardMovieException;
import com.moviemux.movie.usecase.exception.MovieNotFoundException;
import com.moviemux.user.usecase.exception.UserNotFoundException;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/boards")
@RequiredArgsConstructor
public class BoardSpringController {

	private final BoardController controller;

	@PostMapping
	public ResponseEntity<?> createBoard(@RequestBody @Valid BoardRequestDto request,
			@RequestHeader("Authorization") String token) {
		UserTokenDto user = CredentialUtil.getUserFromToken(token);
		BoardResponseDto response = controller.createBoard(request, user);
		return ResponseEntity.status(201).body(response);
	}

	@GetMapping("/{publicId}")
	public ResponseEntity<?> getBoard(@PathVariable UUID publicId,
			@RequestHeader("Authorization") String token) {
		try {
			UserTokenDto user = CredentialUtil.getUserFromToken(token);
			BoardResponseDto response = controller.getBoard(publicId, user);
			return ResponseEntity.ok(response);
		} catch (BoardNotFoundException e) {
			return ResponseEntity.notFound().build();
		}
	}

	@DeleteMapping("/{publicId}")
	public ResponseEntity<?> deleteBoard(@PathVariable UUID publicId,
			@RequestHeader("Authorization") String token) {
		try {
			UserTokenDto user = CredentialUtil.getUserFromToken(token);
			controller.deleteBoard(publicId, user);
			return ResponseEntity.ok().build();
		} catch (BoardNotFoundException e) {
			return ResponseEntity.notFound().build();
		} catch (BoardNotAuthorizedException e) {
			return ResponseEntity.status(403).body(Map.of("error", true, "code", 403, "message", e.getMessage()));
		}
	}

	@PostMapping("/{publicId}/invite/{userPublicId}")
	public ResponseEntity<?> inviteUser(@PathVariable UUID publicId, @PathVariable UUID userPublicId,
			@RequestHeader("Authorization") String token) {
		try {
			UserTokenDto user = CredentialUtil.getUserFromToken(token);
			BoardMemberResponseDto response = controller.inviteUser(publicId, userPublicId, user);
			return ResponseEntity.status(201).body(response);
		} catch (BoardNotFoundException | UserNotFoundException e) {
			return ResponseEntity.notFound().build();
		} catch (BoardNotAuthorizedException e) {
			return ResponseEntity.status(403).body(Map.of("error", true, "code", 403, "message", e.getMessage()));
		} catch (DuplicateBoardMemberException | BoardMemberNotFoundException e) {
			return ResponseEntity.badRequest().body(Map.of("error", true, "code", 400, "message", e.getMessage()));
		}
	}

	@PostMapping("/{publicId}/invite/{userPublicId}/accept")
	public ResponseEntity<?> acceptInvite(@PathVariable UUID publicId, @PathVariable UUID userPublicId,
			@RequestHeader("Authorization") String token) {
		try {
			UserTokenDto user = CredentialUtil.getUserFromToken(token);
			BoardMemberResponseDto response = controller.acceptInvite(publicId, userPublicId, user);
			return ResponseEntity.ok(response);
		} catch (BoardNotFoundException e) {
			return ResponseEntity.notFound().build();
		} catch (BoardMemberNotFoundException | BoardNotAuthorizedException e) {
			return ResponseEntity.badRequest().body(Map.of("error", true, "code", 400, "message", e.getMessage()));
		}
	}

	@PostMapping("/{publicId}/invite/{userPublicId}/reject")
	public ResponseEntity<?> rejectInvite(@PathVariable UUID publicId, @PathVariable UUID userPublicId,
			@RequestHeader("Authorization") String token) {
		try {
			UserTokenDto user = CredentialUtil.getUserFromToken(token);
			BoardMemberResponseDto response = controller.rejectInvite(publicId, userPublicId, user);
			return ResponseEntity.ok(response);
		} catch (BoardNotFoundException e) {
			return ResponseEntity.notFound().build();
		} catch (BoardMemberNotFoundException | BoardNotAuthorizedException e) {
			return ResponseEntity.badRequest().body(Map.of("error", true, "code", 400, "message", e.getMessage()));
		}
	}

	@DeleteMapping("/{publicId}/members/{userPublicId}")
	public ResponseEntity<?> removeMember(@PathVariable UUID publicId, @PathVariable UUID userPublicId,
			@RequestHeader("Authorization") String token) {
		try {
			UserTokenDto user = CredentialUtil.getUserFromToken(token);
			controller.removeMember(publicId, userPublicId, user);
			return ResponseEntity.ok().build();
		} catch (BoardNotFoundException e) {
			return ResponseEntity.notFound().build();
		} catch (BoardNotAuthorizedException e) {
			return ResponseEntity.status(403).body(Map.of("error", true, "code", 403, "message", e.getMessage()));
		} catch (BoardMemberNotFoundException e) {
			return ResponseEntity.badRequest().body(Map.of("error", true, "code", 400, "message", e.getMessage()));
		}
	}

	@PostMapping("/{publicId}/permissions/{userPublicId}")
	public ResponseEntity<?> addPermission(@PathVariable UUID publicId, @PathVariable UUID userPublicId,
			@RequestBody @Valid BoardPermissionRequestDto request,
			@RequestHeader("Authorization") String token) {
		try {
			UserTokenDto user = CredentialUtil.getUserFromToken(token);
			BoardPermissionResponseDto response = controller.addPermission(publicId, userPublicId, request, user);
			return ResponseEntity.status(201).body(response);
		} catch (BoardNotFoundException e) {
			return ResponseEntity.notFound().build();
		} catch (BoardNotAuthorizedException e) {
			return ResponseEntity.status(403).body(Map.of("error", true, "code", 403, "message", e.getMessage()));
		} catch (BoardMemberNotFoundException e) {
			return ResponseEntity.badRequest().body(Map.of("error", true, "code", 400, "message", e.getMessage()));
		}
	}

	@DeleteMapping("/{publicId}/permissions/{userPublicId}/{permission}")
	public ResponseEntity<?> removePermission(@PathVariable UUID publicId, @PathVariable UUID userPublicId,
			@PathVariable String permission,
			@RequestHeader("Authorization") String token) {
		try {
			UserTokenDto user = CredentialUtil.getUserFromToken(token);
			controller.removePermission(publicId, userPublicId, permission, user);
			return ResponseEntity.ok().build();
		} catch (BoardNotFoundException e) {
			return ResponseEntity.notFound().build();
		} catch (BoardNotAuthorizedException e) {
			return ResponseEntity.status(403).body(Map.of("error", true, "code", 403, "message", e.getMessage()));
		} catch (IllegalArgumentException e) {
			return ResponseEntity.badRequest().body(Map.of("error", true, "code", 400, "message", "Invalid permission type: " + permission));
		}
	}

	@PostMapping("/{publicId}/transfer/{userPublicId}")
	public ResponseEntity<?> transferOwnership(@PathVariable UUID publicId, @PathVariable UUID userPublicId,
			@RequestHeader("Authorization") String token) {
		try {
			UserTokenDto user = CredentialUtil.getUserFromToken(token);
			BoardResponseDto response = controller.transferOwnership(publicId, userPublicId, user);
			return ResponseEntity.ok(response);
		} catch (BoardNotFoundException e) {
			return ResponseEntity.notFound().build();
		} catch (BoardNotAuthorizedException e) {
			return ResponseEntity.status(403).body(Map.of("error", true, "code", 403, "message", e.getMessage()));
		} catch (BoardMemberNotFoundException e) {
			return ResponseEntity.badRequest().body(Map.of("error", true, "code", 400, "message", e.getMessage()));
		}
	}

	@PostMapping("/{publicId}/movies/{moviePublicId}")
	public ResponseEntity<?> addMovie(@PathVariable UUID publicId, @PathVariable UUID moviePublicId,
			@RequestHeader("Authorization") String token) {
		try {
			UserTokenDto user = CredentialUtil.getUserFromToken(token);
			BoardMovieResponseDto response = controller.addMovie(publicId, moviePublicId, user);
			return ResponseEntity.status(201).body(response);
		} catch (BoardNotFoundException | MovieNotFoundException e) {
			return ResponseEntity.notFound().build();
		} catch (BoardNotAuthorizedException e) {
			return ResponseEntity.status(403).body(Map.of("error", true, "code", 403, "message", e.getMessage()));
		} catch (DuplicateBoardMovieException | BoardMemberNotFoundException e) {
			return ResponseEntity.badRequest().body(Map.of("error", true, "code", 400, "message", e.getMessage()));
		}
	}

	@DeleteMapping("/{publicId}/movies/{moviePublicId}")
	public ResponseEntity<?> removeMovie(@PathVariable UUID publicId, @PathVariable UUID moviePublicId,
			@RequestHeader("Authorization") String token) {
		try {
			UserTokenDto user = CredentialUtil.getUserFromToken(token);
			controller.removeMovie(publicId, moviePublicId, user);
			return ResponseEntity.ok().build();
		} catch (BoardNotFoundException | BoardMovieNotFoundException e) {
			return ResponseEntity.notFound().build();
		} catch (BoardNotAuthorizedException e) {
			return ResponseEntity.status(403).body(Map.of("error", true, "code", 403, "message", e.getMessage()));
		} catch (BoardMemberNotFoundException e) {
			return ResponseEntity.badRequest().body(Map.of("error", true, "code", 400, "message", e.getMessage()));
		}
	}

	@PostMapping("/{publicId}/movies/{moviePublicId}/rate")
	public ResponseEntity<?> rateMovie(@PathVariable UUID publicId, @PathVariable UUID moviePublicId,
			@RequestBody @Valid BoardMovieRatingRequestDto request,
			@RequestHeader("Authorization") String token) {
		try {
			UserTokenDto user = CredentialUtil.getUserFromToken(token);
			BoardMovieRatingResponseDto response = controller.rateMovie(publicId, moviePublicId, request, user);
			return ResponseEntity.ok(response);
		} catch (BoardNotFoundException | MovieNotFoundException | BoardMovieNotFoundException e) {
			return ResponseEntity.notFound().build();
		} catch (BoardMemberNotFoundException e) {
			return ResponseEntity.badRequest().body(Map.of("error", true, "code", 400, "message", e.getMessage()));
		}
	}

	@DeleteMapping("/{publicId}/movies/{moviePublicId}/rate")
	public ResponseEntity<?> deleteRating(@PathVariable UUID publicId, @PathVariable UUID moviePublicId,
			@RequestHeader("Authorization") String token) {
		try {
			UserTokenDto user = CredentialUtil.getUserFromToken(token);
			controller.deleteRating(publicId, moviePublicId, user);
			return ResponseEntity.ok().build();
		} catch (BoardNotFoundException | MovieNotFoundException | BoardMovieNotFoundException e) {
			return ResponseEntity.notFound().build();
		} catch (BoardMemberNotFoundException e) {
			return ResponseEntity.badRequest().body(Map.of("error", true, "code", 400, "message", e.getMessage()));
		}
	}
}
