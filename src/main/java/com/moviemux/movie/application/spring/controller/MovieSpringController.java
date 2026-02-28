package com.moviemux.movie.application.spring.controller;

import com.moviemux.adapter.core.controller.dto.UserTokenDto;
import com.moviemux.adapter.util.CredentialUtil;
import com.moviemux.movie.adapter.controller.MovieController;
import com.moviemux.movie.adapter.controller.dto.*;
import com.moviemux.movie.usecase.exception.DuplicatedMovieException;
import com.moviemux.movie.usecase.exception.MovieNotFoundException;
import com.moviemux.movie.usecase.exception.MovieNoteNotFoundException;
import com.moviemux.user.usecase.exception.UserNotAuthorizedException;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/movie")
@RequiredArgsConstructor
public class MovieSpringController {

	private final MovieController controller;

	@GetMapping
	public ResponseEntity<?> getAllMoviesTest(@RequestParam(required = false) String title,
			@RequestParam(required = false) String withGenres, @RequestParam(required = false) String sortJoin,
			Pageable pageable, @RequestHeader("Authorization") String token) {
		UserTokenDto user = CredentialUtil.getUserFromToken(token);
		Page<MovieResponseDto> response = controller.listAllMovies(title, withGenres, sortJoin, pageable, user);

		if (response.isEmpty()) {
			return ResponseEntity.noContent().build();
		}

		return ResponseEntity.ok(response);
	}

	@GetMapping("/{movieId}")
	public ResponseEntity<?> getMovieById(@PathVariable UUID movieId, @RequestHeader("Authorization") String token) {
		MovieResponseDto response;
		try {
			UserTokenDto user = CredentialUtil.getUserFromToken(token);
			response = controller.getById(movieId, user);
		} catch (MovieNoteNotFoundException e) {
			return ResponseEntity.noContent().build();
		}

		return ResponseEntity.ok(response);
	}

	@PostMapping
	public ResponseEntity<?> createMovie(@RequestBody @Valid MovieRequestDto request,
			@RequestHeader("Authorization") String token) {
		try {
			UserTokenDto user = CredentialUtil.getUserFromToken(token);
			MovieResponseDto response = controller.save(request, user);
			return ResponseEntity.ok(response);
		} catch (DuplicatedMovieException e) {
			return ResponseEntity.badRequest().body(Map.of("error", true, "code", 400, "message", e.getMessage()));
		}
	}

	@PutMapping("/{movieId}/update")
	public ResponseEntity<?> updateMovie(@RequestBody @Valid MovieRequestDto request, @PathVariable UUID movieId,
			@RequestHeader("Authorization") String token) {

		try {
			UserTokenDto user = CredentialUtil.getUserFromToken(token);
			MovieResponseDto response = controller.update(request, movieId, user);
			return ResponseEntity.ok(response);
		} catch (MovieNotFoundException e) {
			return ResponseEntity.badRequest().body(Map.of("error", true, "code", 400, "message", e.getMessage()));
		} catch (UserNotAuthorizedException e) {
			return ResponseEntity.status(403).body(Map.of("error", true, "code", 400, "message", e.getMessage()));
		}
	}

	@DeleteMapping("/{movieId}/delete")
	public ResponseEntity<?> updateMovie(@PathVariable UUID movieId, @RequestHeader("Authorization") String token) {
		try {
			UserTokenDto user = CredentialUtil.getUserFromToken(token);
			controller.delete(movieId, user);
			return ResponseEntity.ok().build();
		} catch (UserNotAuthorizedException e) {
			return ResponseEntity.status(403).body(Map.of("error", true, "code", 403, "message", e.getMessage()));
		}
	}

	@GetMapping("/genres")
	public ResponseEntity<?> listMovieGenres() {

		List<MovieGenreResponseDto> response = controller.listGenres();

		if (response.isEmpty()) {
			return ResponseEntity.noContent().build();
		}

		return ResponseEntity.ok(response);

	}

	@GetMapping("/genres-with-movies")
	public ResponseEntity<?> listMovieGenresWithMovies() {

		List<MovieGenreResponseDto> response = controller.listAllGenresWithMovies();

		if (response.isEmpty()) {
			return ResponseEntity.noContent().build();
		}

		return ResponseEntity.ok(response);

	}
}
