package com.moviemux.kronusintegrationtool.adapter.repository.rest.impl;

import com.moviemux.kronusintegrationtool.domain.repository.KronusCatalogRepository;
import com.moviemux.kronusintegrationtool.adapter.repository.rest.dto.*;
import com.moviemux.kronusintegrationtool.domain.model.*;
import com.moviemux.kronusintegrationtool.domain.model.CatalogGenre;
import com.moviemux.kronusintegrationtool.adapter.repository.rest.mapper.IntegrationFailureMapper;
import com.moviemux.kronusintegrationtool.adapter.repository.rest.mapper.WatchProvidersMapper;
import com.moviemux.kronusintegrationtool.adapter.repository.rest.mapper.CatalogResponseMapper;
import lombok.extern.log4j.Log4j2;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Repository;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.stream.Collectors;

@Repository
@Log4j2
public class KronusIntegrationToolRepositoryImpl implements KronusCatalogRepository {

	private final RestClient restClientKit;

	public KronusIntegrationToolRepositoryImpl(@Qualifier("restClientKit") RestClient restClientKit) {
		this.restClientKit = restClientKit;
	}

	@Override
	public MovieSummary movieSummary(Long tmdbId) {
		final String uri = "/api/v1/tmdb/movie/%s/summary".formatted(tmdbId);
		try {
			MovieSummaryResponseDto response = restClientKit.get()
					.uri(uri)
					.retrieve()
					.body(MovieSummaryResponseDto.class);

			java.util.Objects.requireNonNull(response, "Missing integration response");
			return CatalogResponseMapper.summary(response);
		} catch (Exception e) {
			log.error("Error with KIT API request at %s".formatted(uri), e);
			throw IntegrationFailureMapper.map(e);
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
			java.util.Objects.requireNonNull(response, "Missing integration response");

			return CatalogResponseMapper.search(response);
		} catch (Exception e) {
			log.error("Error with KIT API request at %s".formatted(uri), e);
			throw IntegrationFailureMapper.map(e);
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
			java.util.Objects.requireNonNull(response, "Missing integration response");

			return CatalogResponseMapper.search(response);
		} catch (Exception e) {
			log.error("Error with KIT API request at %s".formatted(uri), e);
			throw IntegrationFailureMapper.map(e);
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
			java.util.Objects.requireNonNull(response, "Missing integration response");

			return CatalogResponseMapper.search(response);
		} catch (Exception e) {
			log.error("Error with KIT API request at %s".formatted(uri), e);
			throw IntegrationFailureMapper.map(e);
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
			java.util.Objects.requireNonNull(response, "Missing integration response");

			return CatalogResponseMapper.search(response);
		} catch (Exception e) {
			log.error("Error with KIT API request at %s".formatted(uri), e);
			throw IntegrationFailureMapper.map(e);
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
			java.util.Objects.requireNonNull(response, "Missing integration response");

			return CatalogResponseMapper.search(response);
		} catch (Exception e) {
			log.error("Error with KIT API request at %s".formatted(uri), e);
			throw IntegrationFailureMapper.map(e);
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
			java.util.Objects.requireNonNull(response, "Missing integration response");

			return CatalogResponseMapper.search(response);
		} catch (Exception e) {
			log.error("Error with KIT API request at %s".formatted(uri), e);
			throw IntegrationFailureMapper.map(e);
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
			java.util.Objects.requireNonNull(response, "Missing integration response");

			return CatalogResponseMapper.search(response);
		} catch (Exception e) {
			log.error("Error with KIT API request at %s".formatted(uri), e);
			throw IntegrationFailureMapper.map(e);
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
			java.util.Objects.requireNonNull(response, "Missing integration response");

			return CatalogResponseMapper.search(response);
		} catch (Exception e) {
			log.error("Error with KIT API request at %s".formatted(uri), e);
			throw IntegrationFailureMapper.map(e);
		}
	}

	@Override
	public List<CatalogGenre> listGenres() {
		final String uri = "/api/v1/tmdb/genre/movie/list?language=%s".formatted("pt-Br");
		try {
			MovieGenresResponseDto response = restClientKit.get()
					.uri(uri)
					.accept(MediaType.APPLICATION_JSON)
					.retrieve()
					.body(MovieGenresResponseDto.class);
			java.util.Objects.requireNonNull(response, "Missing integration response");

			return response.getGenres().stream().map(CatalogResponseMapper::genre).collect(Collectors.toList());
		} catch (Exception e) {
			log.error("Error with KIT API request at %s".formatted(uri), e);
			throw IntegrationFailureMapper.map(e);
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
			java.util.Objects.requireNonNull(response, "Missing integration response");

			return WatchProvidersMapper.toDomain(response);
		} catch (Exception e) {
			log.error("Error with KIT API request at %s".formatted(uri), e);
			throw IntegrationFailureMapper.map(e);
		}
	}

	@Override
	public Credit getCredits(Long tmdbId) {
		final String uri = "/api/v1/tmdb/movie/%s/credits".formatted(tmdbId);
		try {
			CreditResponseDto response = restClientKit.get()
					.uri(uri)
					.retrieve()
					.body(CreditResponseDto.class);
			java.util.Objects.requireNonNull(response, "Missing integration response");

			return CatalogResponseMapper.credits(response);
		} catch (Exception e) {
			log.error("Error with KIT API request at %s".formatted(uri), e);
			throw IntegrationFailureMapper.map(e);
		}
	}

}
