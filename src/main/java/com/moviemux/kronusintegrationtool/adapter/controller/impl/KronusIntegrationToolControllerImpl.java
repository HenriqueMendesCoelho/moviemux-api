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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;

@Controller
public class KronusIntegrationToolControllerImpl implements KronusIntegrationToolController {

	@Autowired
	private MovieSumaryUseCase movieSumaryUseCase;

	@Autowired
	private SearchCatalogMoviesUseCase searchByNameUseCase;
	@Autowired
	private PopularMoviesUseCase moviesPopularUseCase;
	@Autowired
	private NowPlayingMoviesUseCase moviesNowPlayingUseCase;
	@Autowired
	private UpcomingMoviesUseCase moviesUpcomingUseCase;
	@Autowired
	private TopRatedMoviesUseCase moviesTopRatedUseCase;
	@Autowired
	private RecommendedMoviesUseCase moviesRecommendationsUseCase;
	@Autowired
	private SimilarMoviesUseCase moviesSimilarUseCase;
	@Autowired
	private DiscoverMoviesUseCase discoverMoviesUseCase;

	@Autowired
	private SearchWatchProviedersUseCase searchWatchProviedersUseCase;

	@Autowired
	private SearchCreditUseCase searchCreditUseCase;

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
