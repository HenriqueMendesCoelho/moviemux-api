package com.moviemux.kronusintegrationtool.adapter.repository.rest;

import com.moviemux.kronusintegrationtool.domain.Credit;
import com.moviemux.kronusintegrationtool.domain.MovieSearch;
import com.moviemux.kronusintegrationtool.domain.MovieSummary;
import com.moviemux.kronusintegrationtool.domain.WatchProviders;
import com.moviemux.movie.domain.MovieGenre;

import java.util.List;

public interface KronusIntegrationToolRepository {

	MovieSummary movieSummary(Long tmdbId);

	MovieSearch searchByName(String name, Integer page, String language, boolean includeAdult);

	MovieSearch moviesPopular(Integer page);

	MovieSearch moviesNowPlaying(Integer page);

	MovieSearch moviesUpcoming(Integer page);

	MovieSearch moviesTopRated(Integer page);

	MovieSearch moviesRecommendations(Long movieTmdbId, Integer page);

	MovieSearch moviesSimilar(Long movieTmdbId, Integer page);

	WatchProviders getWatchProviders(Long tmdbId);

	MovieSearch discoverMovies(String sortByParam, Integer page, Integer primaryReleaseYear, String with_genres,
			String without_genres);

	List<MovieGenre> listGenres();

	Credit getCredits(Long tmdbId);
}
