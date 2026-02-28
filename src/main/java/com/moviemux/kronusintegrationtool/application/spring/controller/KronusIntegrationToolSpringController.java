package com.moviemux.kronusintegrationtool.application.spring.controller;

import com.moviemux.kronusintegrationtool.adapter.controller.KronusIntegrationToolController;
import com.moviemux.kronusintegrationtool.adapter.controller.dto.CreditResponseDto;
import com.moviemux.kronusintegrationtool.adapter.controller.dto.MovieSearchResponseDto;
import com.moviemux.kronusintegrationtool.adapter.controller.dto.MovieSummaryResponseDto;
import com.moviemux.kronusintegrationtool.adapter.controller.dto.WatchProvidersResponseDto;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/movie/tmdb")
@RequiredArgsConstructor
public class KronusIntegrationToolSpringController {

	private final KronusIntegrationToolController controller;

	@GetMapping("/{movieId}/summary")
	public ResponseEntity<?> summaryMovieTmdb(@PathVariable Long movieId) {
		MovieSummaryResponseDto response = controller.movieSummary(movieId);

		if (response == null) {
			return ResponseEntity.noContent().build();
		}

		return ResponseEntity.ok(response);

	}

	@GetMapping
	public ResponseEntity<?> searchByName(@RequestParam(required = true) String query,
			@RequestParam(required = false, defaultValue = "1") Integer page,
			@RequestParam(required = false, defaultValue = "pt-Br") String language) {
		MovieSearchResponseDto response = controller.movieSearchByName(query, page, language);

		return ResponseEntity.ok(response);

	}

	@GetMapping("/popular")
	public ResponseEntity<?> moviesPopular(@RequestParam(required = false, defaultValue = "1") Integer page) {
		MovieSearchResponseDto response = controller.moviesPopular(page);

		return ResponseEntity.ok(response);
	}

	@GetMapping("/now-playing")
	public ResponseEntity<?> moviesNowPlaying(@RequestParam(required = false, defaultValue = "1") Integer page) {
		MovieSearchResponseDto response = controller.moviesNowPlaying(page);

		return ResponseEntity.ok(response);
	}

	@GetMapping("/upcoming")
	public ResponseEntity<?> moviesUpcoming(@RequestParam(required = false, defaultValue = "1") Integer page) {
		MovieSearchResponseDto response = controller.moviesUpcoming(page);

		return ResponseEntity.ok(response);
	}

	@GetMapping("/top-rated")
	public ResponseEntity<?> moviesTopRated(@RequestParam(required = false, defaultValue = "1") Integer page) {
		MovieSearchResponseDto response = controller.moviesTopRated(page);

		return ResponseEntity.ok(response);
	}

	@GetMapping("/{movieId}/recommendations")
	public ResponseEntity<?> moviesRecommendations(@PathVariable Long movieId,
			@RequestParam(required = false, defaultValue = "1") Integer page) {
		MovieSearchResponseDto response = controller.moviesRecommendations(movieId, page);

		return ResponseEntity.ok(response);
	}

	@GetMapping("/{movieId}/similar")
	public ResponseEntity<?> moviesSimilar(@PathVariable Long movieId,
			@RequestParam(required = false, defaultValue = "1") Integer page) {
		MovieSearchResponseDto response = controller.moviesSimilar(movieId, page);

		return ResponseEntity.ok(response);
	}

	@GetMapping("/discover")
	public ResponseEntity<?> discoverMovies(@RequestParam(required = false) String sort,
			@RequestParam(required = false, defaultValue = "1") Integer page,
			@RequestParam(required = false) Integer year, @RequestParam(required = false) String with_genres,
			@RequestParam(required = false) String without_genres) {
		MovieSearchResponseDto response = controller.discoverMovies(sort, page, year, with_genres, without_genres);

		return ResponseEntity.ok(response);
	}

	@GetMapping("/{movieId}/watch-providers")
	public ResponseEntity<?> getWatchProviders(@PathVariable Long movieId) {
		WatchProvidersResponseDto response = controller.searchWatchProvidersByMovieId(movieId);

		return ResponseEntity.ok(response);
	}

	@GetMapping("/{movieId}/credits")
	public ResponseEntity<?> getCredits(@PathVariable Long movieId) {
		CreditResponseDto response = controller.searchMovieCredits(movieId);

		return ResponseEntity.ok(response);
	}

}
