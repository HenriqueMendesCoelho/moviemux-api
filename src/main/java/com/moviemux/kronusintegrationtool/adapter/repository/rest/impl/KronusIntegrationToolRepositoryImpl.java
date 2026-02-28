package com.moviemux.kronusintegrationtool.adapter.repository.rest.impl;

import com.moviemux.kronusintegrationtool.adapter.repository.rest.KronusIntegrationToolRepository;
import com.moviemux.kronusintegrationtool.adapter.repository.rest.dto.*;
import com.moviemux.kronusintegrationtool.domain.Credit;
import com.moviemux.kronusintegrationtool.domain.MovieSearch;
import com.moviemux.kronusintegrationtool.domain.MovieSummary;
import com.moviemux.kronusintegrationtool.domain.WatchProviders;
import com.moviemux.movie.domain.MovieGenre;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.apache.commons.lang3.StringUtils;
import org.springframework.http.MediaType;
import org.springframework.security.web.firewall.RequestRejectedException;
import org.springframework.stereotype.Repository;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.stream.Collectors;

@Repository
@Log4j2
@RequiredArgsConstructor
public class KronusIntegrationToolRepositoryImpl implements KronusIntegrationToolRepository {

	private final RestClient restClientKit;

	@Override
	public MovieSummary movieSummary(Long tmdbId) {
		final String uri = "/api/v1/tmdb/movie/%s/summary".formatted(tmdbId);
		try {
			MovieSummaryResponseDto response = restClientKit.get()
					.uri(uri)
					.retrieve()
					.body(MovieSummaryResponseDto.class);

			assert response != null;
			return response.toDomain();
		} catch (Exception e) {
			log.error("Error with KIT API request at %s".formatted(uri), e);
			throw new RequestRejectedException(e.getMessage());
		}

	}

	@Override
	public MovieSearch searchByName(String name, Integer page, String language, boolean includeAdult) {
		final String uri = "/api/v1/tmdb/search/movie?query=%s&page=%s&language=%s&include_adult=%s".formatted(name,
				page, language, includeAdult);
		try {
			MovieSearchResponseDto response = restClientKit.get()
					.uri(uri)
					.retrieve()
					.body(MovieSearchResponseDto.class);
			assert response != null;

			return response.toDomain();
		} catch (Exception e) {
			log.error("Error with KIT API request at %s".formatted(uri), e);
			throw new RequestRejectedException(e.getMessage());
		}
	}

	@Override
	public MovieSearch moviesPopular(Integer page) {
		final String uri = "/api/v1/tmdb/movie/popular?page=%s&language=%s&region=%s".formatted(page, "pt-Br", "BR");
		try {
			MovieSearchResponseDto response = restClientKit.get()
					.uri(uri)
					.retrieve()
					.body(MovieSearchResponseDto.class);
			assert response != null;

			return response.toDomain();
		} catch (Exception e) {
			log.error("Error with KIT API request at %s".formatted(uri), e);
			throw new RequestRejectedException(e.getMessage());
		}
	}

	@Override
	public MovieSearch moviesNowPlaying(Integer page) {
		final String uri = "/api/v1/tmdb/movie/now_playing?page=%s&language=%s&region=%s".formatted(page, "pt-Br",
				"BR");
		try {
			MovieSearchResponseDto response = restClientKit.get()
					.uri(uri)
					.retrieve()
					.body(MovieSearchResponseDto.class);
			assert response != null;

			return response.toDomain();
		} catch (Exception e) {
			log.error("Error with KIT API request at %s".formatted(uri), e);
			throw new RequestRejectedException(e.getMessage());
		}
	}

	@Override
	public MovieSearch moviesUpcoming(Integer page) {
		final String uri = "/api/v1/tmdb/movie/upcoming?page=%s&language=%s&region=%s".formatted(page, "pt-Br", "BR");
		try {
			MovieSearchResponseDto response = restClientKit.get()
					.uri(uri)
					.retrieve()
					.body(MovieSearchResponseDto.class);
			assert response != null;

			return response.toDomain();
		} catch (Exception e) {
			log.error("Error with KIT API request at %s".formatted(uri), e);
			throw new RequestRejectedException(e.getMessage());
		}
	}

	@Override
	public MovieSearch moviesTopRated(Integer page) {
		final String uri = "/api/v1/tmdb/movie/top_rated?page=%s&language=%s&region=%s".formatted(page, "pt-Br", "BR");
		try {
			MovieSearchResponseDto response = restClientKit.get()
					.uri(uri)
					.retrieve()
					.body(MovieSearchResponseDto.class);
			assert response != null;

			return response.toDomain();
		} catch (Exception e) {
			log.error("Error with KIT API request at %s".formatted(uri), e);
			throw new RequestRejectedException(e.getMessage());
		}
	}

	@Override
	public MovieSearch moviesRecommendations(Long movieTmdbId, Integer page) {
		final String uri = "/api/v1/tmdb/movie/%s/recommendations?page=%s&language=%s".formatted(movieTmdbId, page,
				"pt-Br");
		try {
			MovieSearchResponseDto response = restClientKit.get()
					.uri(uri)
					.retrieve()
					.body(MovieSearchResponseDto.class);
			assert response != null;

			return response.toDomain();
		} catch (Exception e) {
			log.error("Error with KIT API request at %s".formatted(uri), e);
			throw new RequestRejectedException(e.getMessage());
		}
	}

	@Override
	public MovieSearch moviesSimilar(Long movieTmdbId, Integer page) {
		final String uri = "/api/v1/tmdb/movie/%s/similar?page=%s&language=%s".formatted(movieTmdbId, page, "pt-Br");
		try {
			MovieSearchResponseDto response = restClientKit.get()
					.uri(uri)
					.retrieve()
					.body(MovieSearchResponseDto.class);
			assert response != null;

			return response.toDomain();
		} catch (Exception e) {
			log.error("Error with KIT API request at %s".formatted(uri), e);
			throw new RequestRejectedException(e.getMessage());
		}
	}

	@Override
	public MovieSearch discoverMovies(String sortByParam, Integer page, Integer primaryReleaseYear, String withGenres,
			String withoutGenres) {
		final StringBuilder uri = new StringBuilder(
				"/api/v1/tmdb/discover/movie?page=%s&language=%s&include_adult=false&vote_count.gte=300".formatted(page,
						"pt-Br"));

		if (StringUtils.isNotEmpty(sortByParam)) {
			uri.append("&sort_by=%s".formatted(sortByParam));
		}

		if (primaryReleaseYear != null) {
			uri.append("&primary_release_year=%s".formatted(primaryReleaseYear));
		}

		if (StringUtils.isNotEmpty(withGenres)) {
			uri.append("&with_genres=%s".formatted(withGenres));
		}

		if (StringUtils.isNotEmpty(withoutGenres)) {
			uri.append("&without_genres=%s".formatted(withoutGenres));
		}

		try {
			MovieSearchResponseDto response = restClientKit.get()
					.uri(uri.toString())
					.retrieve()
					.body(MovieSearchResponseDto.class);
			assert response != null;

			return response.toDomain();
		} catch (Exception e) {
			log.error("Error with KIT API request at %s".formatted(uri), e);
			throw new RequestRejectedException(e.getMessage());
		}
	}

	@Override
	public List<MovieGenre> listGenres() {
		final String uri = "/api/v1/tmdb/genre/movie/list?language=%s".formatted("pt-Br");
		try {
			MovieGenresResponseDto r = restClientKit.get()
					.uri(uri)
					.accept(MediaType.APPLICATION_JSON)
					.retrieve()
					.body(MovieGenresResponseDto.class);
			assert r != null;

			return r.getGenres().stream().map(MovieGenreResponseDto::toDomain).collect(Collectors.toList());
		} catch (Exception e) {
			log.error("Error with KIT API request at %s".formatted(uri), e);
			throw new RequestRejectedException(e.getMessage());
		}
	}

	@Override
	public WatchProviders getWatchProviders(Long tmdbId) {
		final String uri = "/api/v1/tmdb/movie/%s/watch/providers".formatted(tmdbId);
		try {
			WatchProvidersResponseDto response = restClientKit.get()
					.uri(uri)
					.retrieve()
					.body(WatchProvidersResponseDto.class);
			assert response != null;

			return response.toDomain();
		} catch (Exception e) {
			log.error("Error with KIT API request at %s".formatted(uri), e);
			throw new RequestRejectedException(e.getMessage());
		}
	}

	@Override
	public Credit getCredits(Long tmdbId) {
		final String uri = "/api/v1/tmdb/movie/%s/credits".formatted(tmdbId);
		try {
			CreditResponseDto response = restClientKit.get().uri(uri).retrieve().body(CreditResponseDto.class);
			assert response != null;

			return response.toDomain();
		} catch (Exception e) {
			log.error("Error with KIT API request at %s".formatted(uri), e);
			throw new RequestRejectedException(e.getMessage());
		}
	}

}
