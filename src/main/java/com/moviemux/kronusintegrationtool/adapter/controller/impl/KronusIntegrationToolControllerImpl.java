package com.moviemux.kronusintegrationtool.adapter.controller.impl;

import com.moviemux.kronusintegrationtool.adapter.controller.KronusIntegrationToolController;
import com.moviemux.kronusintegrationtool.adapter.controller.dto.*;
import com.moviemux.kronusintegrationtool.domain.Credit;
import com.moviemux.kronusintegrationtool.domain.MovieSearch;
import com.moviemux.kronusintegrationtool.domain.MovieSummary;
import com.moviemux.kronusintegrationtool.domain.WatchProviders;
import com.moviemux.kronusintegrationtool.usecase.MovieSumaryUseCase;
import com.moviemux.kronusintegrationtool.usecase.SearchCreditUseCase;
import com.moviemux.kronusintegrationtool.usecase.SearchMovieTmdbUseCase;
import com.moviemux.kronusintegrationtool.usecase.SearchWatchProviedersUseCase;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;

@Controller
@RequiredArgsConstructor
public class KronusIntegrationToolControllerImpl implements KronusIntegrationToolController {

	private final MovieSumaryUseCase movieSumaryUseCase;
	private final SearchMovieTmdbUseCase searchMovieTmdbUseCase;
	private final SearchWatchProviedersUseCase searchWatchProviedersUseCase;
	private final SearchCreditUseCase searchCreditUseCase;

	@Override
	public MovieSummaryResponseDto movieSummary(Long tmdbId) {
		MovieSummary response = movieSumaryUseCase.execute(tmdbId);
		return new MovieSummaryResponseDto(response);
	}

	@Override
	public MovieInfoResponseDto movieInfo(Long tmdbId) {
		MovieSummary response = movieSumaryUseCase.execute(tmdbId);
		return new MovieInfoResponseDto(response);
	}

	@Override
	public MovieSearchResponseDto movieSearchByName(String name, Integer page, String language) {
		MovieSearch response = searchMovieTmdbUseCase.searchByName(name, page, language);
		return new MovieSearchResponseDto(response);
	}

	@Override
	public MovieSearchResponseDto moviesPopular(Integer page) {
		MovieSearch response = searchMovieTmdbUseCase.moviesPopular(page);
		return new MovieSearchResponseDto(response);
	}

	@Override
	public MovieSearchResponseDto moviesNowPlaying(Integer page) {
		MovieSearch response = searchMovieTmdbUseCase.moviesNowPlaying(page);
		return new MovieSearchResponseDto(response);
	}

	@Override
	public MovieSearchResponseDto moviesUpcoming(Integer page) {
		MovieSearch response = searchMovieTmdbUseCase.moviesUpcoming(page);
		return new MovieSearchResponseDto(response);
	}

	@Override
	public MovieSearchResponseDto moviesTopRated(Integer page) {
		MovieSearch response = searchMovieTmdbUseCase.moviesTopRated(page);
		return new MovieSearchResponseDto(response);
	}

	@Override
	public MovieSearchResponseDto moviesRecommendations(Long movieTmdbId, Integer page) {
		MovieSearch response = searchMovieTmdbUseCase.moviesRecommendations(movieTmdbId, page);
		return new MovieSearchResponseDto(response);
	}

	@Override
	public MovieSearchResponseDto moviesSimilar(Long movieTmdbId, Integer page) {
		MovieSearch response = searchMovieTmdbUseCase.moviesSimilar(movieTmdbId, page);
		return new MovieSearchResponseDto(response);
	}

	@Override
	public MovieSearchResponseDto discoverMovies(String sortByParam, Integer page, Integer primaryReleaseYear,
			String withGenres, String withoutGenres) {
		MovieSearch response = searchMovieTmdbUseCase.discoverMovies(sortByParam, page, primaryReleaseYear, withGenres,
				withoutGenres);
		return new MovieSearchResponseDto(response);
	}

	@Override
	public WatchProvidersResponseDto searchWatchProvidersByMovieId(Long movieId) {
		WatchProviders response = searchWatchProviedersUseCase.searchByMovieId(movieId);
		return new WatchProvidersResponseDto(response);
	}

	@Override
	public CreditResponseDto searchMovieCredits(Long movieId) {
		Credit response = searchCreditUseCase.getMovieCredits(movieId);
		return new CreditResponseDto(response);
	}

}
