package com.moviemux.discord.application.spring.controller;

import com.moviemux.legacyboard.application.spring.LegacyBoardEndpoint;
import com.moviemux.discord.adapter.controller.DiscordController;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/restricted/discord")
@RequiredArgsConstructor
public class DiscordRestrictedSpringController {

	private final DiscordController controller;

	@LegacyBoardEndpoint
	@PostMapping("/{movieId}")
	@PreAuthorize("hasRole('ADM')")
	public ResponseEntity<?> sendMovieMessageDiscord(@PathVariable UUID movieId) {
		controller.execute(movieId);

		return ResponseEntity.ok().build();
	}

	@LegacyBoardEndpoint
	@PatchMapping("/{movieId}")
	@PreAuthorize("hasRole('ADM')")
	public ResponseEntity<?> updateMovieMessageDiscord(@PathVariable UUID movieId) {
		controller.update(movieId);

		return ResponseEntity.ok().build();
	}

}
