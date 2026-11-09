package com.moviemux.kronusintegrationtool.adapter.controller.impl;

import com.moviemux.kronusintegrationtool.adapter.controller.KronusIntegrationToolController;
import com.moviemux.kronusintegrationtool.adapter.controller.dto.*;
import com.moviemux.kronusintegrationtool.domain.model.Credit;
import com.moviemux.kronusintegrationtool.domain.model.MovieSearch;
import com.moviemux.kronusintegrationtool.domain.model.MovieSummary;
import com.moviemux.kronusintegrationtool.domain.model.WatchProviders;
import com.moviemux.kronusintegrationtool.domain.usecase.MovieSumaryUseCase;
import com.moviemux.kronusintegrationtool.domain.usecase.SearchCreditUseCase;
import com.moviemux.kronusintegrationtool.domain.usecase.SearchCatalogMoviesUseCase;
import com.moviemux.kronusintegrationtool.domain.usecase.PopularMoviesUseCase;
import com.moviemux.kronusintegrationtool.domain.usecase.NowPlayingMoviesUseCase;
import com.moviemux.kronusintegrationtool.domain.usecase.UpcomingMoviesUseCase;
import com.moviemux.kronusintegrationtool.domain.usecase.TopRatedMoviesUseCase;
import com.moviemux.kronusintegrationtool.domain.usecase.RecommendedMoviesUseCase;
import com.moviemux.kronusintegrationtool.domain.usecase.SimilarMoviesUseCase;
import com.moviemux.kronusintegrationtool.domain.usecase.DiscoverMoviesUseCase;
import com.moviemux.kronusintegrationtool.domain.usecase.SearchWatchProviedersUseCase;
import org.springframework.stereotype.Controller;

@Controller
public class KronusIntegrationToolControllerImpl implements KronusIntegrationToolController {

	private final MovieSumaryUseCase movieSumaryUseCase;

	private final SearchCatalogMoviesUseCase searchByNameUseCase;
	private final PopularMoviesUseCase moviesPopularUseCase;
	private final NowPlayingMoviesUseCase moviesNowPlayingUseCase;
	private final UpcomingMoviesUseCase moviesUpcomingUseCase;
	private final TopRatedMoviesUseCase moviesTopRatedUseCase;
	private final RecommendedMoviesUseCase moviesRecommendationsUseCase;
	private final SimilarMoviesUseCase moviesSimilarUseCase;
	private final DiscoverMoviesUseCase discoverMoviesUseCase;

	private final SearchWatchProviedersUseCase searchWatchProviedersUseCase;

	private final SearchCreditUseCase searchCreditUseCase;

	public KronusIntegrationToolControllerImpl(MovieSumaryUseCase movieSumaryUseCase, SearchCatalogMoviesUseCase searchByNameUseCase, PopularMoviesUseCase moviesPopularUseCase, NowPlayingMoviesUseCase moviesNowPlayingUseCase, UpcomingMoviesUseCase moviesUpcomingUseCase, TopRatedMoviesUseCase moviesTopRatedUseCase, RecommendedMoviesUseCase moviesRecommendationsUseCase, SimilarMoviesUseCase moviesSimilarUseCase, DiscoverMoviesUseCase discoverMoviesUseCase, SearchWatchProviedersUseCase searchWatchProviedersUseCase, SearchCreditUseCase searchCreditUseCase) {
		this.movieSumaryUseCase = movieSumaryUseCase;
		this.searchByNameUseCase = searchByNameUseCase;
		this.moviesPopularUseCase = moviesPopularUseCase;
		this.moviesNowPlayingUseCase = moviesNowPlayingUseCase;
		this.moviesUpcomingUseCase = moviesUpcomingUseCase;
		this.moviesTopRatedUseCase = moviesTopRatedUseCase;
		this.moviesRecommendationsUseCase = moviesRecommendationsUseCase;
		this.moviesSimilarUseCase = moviesSimilarUseCase;
		this.discoverMoviesUseCase = discoverMoviesUseCase;
		this.searchWatchProviedersUseCase = searchWatchProviedersUseCase;
		this.searchCreditUseCase = searchCreditUseCase;
	}

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
		MovieSearch response = searchByNameUseCase.execute(name, page, language);
		return new MovieSearchResponseDto(response);
	}

	@Override
	public MovieSearchResponseDto moviesPopular(Integer page) {
		MovieSearch response = moviesPopularUseCase.execute(page);
		return new MovieSearchResponseDto(response);
	}

	@Override
	public MovieSearchResponseDto moviesNowPlaying(Integer page) {
		MovieSearch response = moviesNowPlayingUseCase.execute(page);
		return new MovieSearchResponseDto(response);
	}

	@Override
	public MovieSearchResponseDto moviesUpcoming(Integer page) {
		MovieSearch response = moviesUpcomingUseCase.execute(page);
		return new MovieSearchResponseDto(response);
	}

	@Override
	public MovieSearchResponseDto moviesTopRated(Integer page) {
		MovieSearch response = moviesTopRatedUseCase.execute(page);
		return new MovieSearchResponseDto(response);
	}

	@Override
	public MovieSearchResponseDto moviesRecommendations(Long movieTmdbId, Integer page) {
		MovieSearch response = moviesRecommendationsUseCase.execute(movieTmdbId, page);
		return new MovieSearchResponseDto(response);
	}

	@Override
	public MovieSearchResponseDto moviesSimilar(Long movieTmdbId, Integer page) {
		MovieSearch response = moviesSimilarUseCase.execute(movieTmdbId, page);
		return new MovieSearchResponseDto(response);
	}

	@Override
	public MovieSearchResponseDto discoverMovies(String sortByParam, Integer page, Integer primaryReleaseYear,
			String withGenres, String withoutGenres) {
		MovieSearch response = discoverMoviesUseCase.execute(sortByParam, page, primaryReleaseYear, withGenres,
				withoutGenres);
		return new MovieSearchResponseDto(response);
	}

	@Override
	public WatchProvidersResponseDto searchWatchProvidersByMovieId(Long movieId) {
		WatchProviders response = searchWatchProviedersUseCase.execute(movieId);
		return new WatchProvidersResponseDto(response);
	}

	@Override
	public CreditResponseDto searchMovieCredits(Long movieId) {
		Credit response = searchCreditUseCase.execute(movieId);
		return new CreditResponseDto(response);
	}

}
