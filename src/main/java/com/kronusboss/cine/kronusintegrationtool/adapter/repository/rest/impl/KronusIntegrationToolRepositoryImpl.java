package com.kronusboss.cine.kronusintegrationtool.adapter.repository.rest.impl;

import java.util.List;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.security.web.firewall.RequestRejectedException;
import org.springframework.stereotype.Repository;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponents;
import org.springframework.web.util.UriComponentsBuilder;

import com.kronusboss.cine.kronusintegrationtool.adapter.repository.rest.KronusIntegrationToolRepository;
import com.kronusboss.cine.kronusintegrationtool.adapter.repository.rest.dto.MovieGenreResponseDto;
import com.kronusboss.cine.kronusintegrationtool.adapter.repository.rest.dto.MovieGenresResponseDto;
import com.kronusboss.cine.kronusintegrationtool.adapter.repository.rest.dto.MovieSearchResponseDto;
import com.kronusboss.cine.kronusintegrationtool.adapter.repository.rest.dto.MovieSummaryResponseDto;
import com.kronusboss.cine.kronusintegrationtool.adapter.repository.rest.dto.SendMailTemplateRequestDto;
import com.kronusboss.cine.kronusintegrationtool.domain.MovieSearch;
import com.kronusboss.cine.kronusintegrationtool.domain.MovieSummary;
import com.kronusboss.cine.kronusintegrationtool.domain.SendMailTemplate;
import com.kronusboss.cine.movie.domain.MovieGenre;

import lombok.extern.log4j.Log4j2;

@Repository
@Log4j2
public class KronusIntegrationToolRepositoryImpl implements KronusIntegrationToolRepository {

	@Value("${kit.key}")
	private String APIKEY;

	@Value("${kit.url}")
	private String URL_KIT;

	@Value("${send.mail}")
	private boolean sendMail;

	@Override
	public MovieSummary movieSummary(Long tmdbId) {

		RestTemplate template = getRestTemplate();
		String uri = createUri(String.format("/api/v1/tmdb/movie/%s/summary", tmdbId));

		try {
			ResponseEntity<MovieSummaryResponseDto> responseEntity = template.exchange(uri, HttpMethod.GET, null,
					MovieSummaryResponseDto.class);

			MovieSummaryResponseDto response = responseEntity.getBody();

			return response.toDomain();
		} catch (Exception e) {
			log.error(e.getMessage());
			throw new RequestRejectedException(e.getMessage());
		}

	}

	@Override
	public MovieSearch searchByName(String name, Integer page, String language, boolean includeAdult) {
		RestTemplate template = getRestTemplate();
		String uri = createUri(String.format("/api/v1/tmdb/search/movie?query=%s&page=%s&language=%s&include_adult=%s",
				name, page, language, includeAdult));

		try {
			ResponseEntity<MovieSearchResponseDto> responseEntity = template.exchange(uri, HttpMethod.GET, null,
					MovieSearchResponseDto.class);

			MovieSearchResponseDto response = responseEntity.getBody();

			return response.toDomain();
		} catch (Exception e) {
			log.error(e.getMessage());
			throw new RequestRejectedException(e.getMessage());
		}
	}

	@Override
	public void sendMailTemplate(SendMailTemplate request) {

		if (!sendMail) {
			return;
		}

		SendMailTemplateRequestDto entity = new SendMailTemplateRequestDto(request);
		RestTemplate template = getRestTemplate();
		String uri = createUri("/api/v1/sendgrid/template");

		HttpEntity<SendMailTemplateRequestDto> requestHttp = new HttpEntity<SendMailTemplateRequestDto>(entity);

		try {
			ResponseEntity<Void> response = template.exchange(uri, HttpMethod.POST, requestHttp, Void.class);
			if (response.getStatusCode().is4xxClientError() || response.getStatusCode().is4xxClientError()) {
				log.error(String.format("Error to send mail to: %s", entity.toString()));
				log.error(String.format("Request returns status code: %s", response.getStatusCode()));
			}
		} catch (Exception e) {
			log.error(e.getMessage());
			throw new RequestRejectedException(e.getMessage());
		}

	}

	@Override
	public MovieSearch moviesPopular(Integer page) {
		RestTemplate template = getRestTemplate();
		String uri = createUri(
				String.format("/api/v1/tmdb/movie/popular?page=%s&language=%s&region=&s", page, "pt-Br", "BR"));

		try {
			ResponseEntity<MovieSearchResponseDto> responseEntity = template.exchange(uri, HttpMethod.GET, null,
					MovieSearchResponseDto.class);

			MovieSearchResponseDto response = responseEntity.getBody();

			return response.toDomain();
		} catch (Exception e) {
			log.error(e.getMessage());
			throw new RequestRejectedException(e.getMessage());
		}
	}

	@Override
	public MovieSearch moviesNowPlaying(Integer page) {
		RestTemplate template = getRestTemplate();
		String uri = createUri(
				String.format("/api/v1/tmdb/movie/now_playing?page=%s&language=%s&region=&s", page, "pt-Br", "BR"));

		try {
			ResponseEntity<MovieSearchResponseDto> responseEntity = template.exchange(uri, HttpMethod.GET, null,
					MovieSearchResponseDto.class);

			MovieSearchResponseDto response = responseEntity.getBody();

			return response.toDomain();
		} catch (Exception e) {
			log.error(e.getMessage());
			throw new RequestRejectedException(e.getMessage());
		}
	}

	@Override
	public MovieSearch moviesTopRated(Integer page) {
		RestTemplate template = getRestTemplate();
		String uri = createUri(
				String.format("/api/v1/tmdb/movie/top_rated?page=%s&language=%s&region=&s", page, "pt-Br", "BR"));

		try {
			ResponseEntity<MovieSearchResponseDto> responseEntity = template.exchange(uri, HttpMethod.GET, null,
					MovieSearchResponseDto.class);

			MovieSearchResponseDto response = responseEntity.getBody();

			return response.toDomain();
		} catch (Exception e) {
			log.error(e.getMessage());
			throw new RequestRejectedException(e.getMessage());
		}
	}

	@Override
	public MovieSearch moviesRecommendations(Long movieTmdbId, Integer page) {
		RestTemplate template = getRestTemplate();
		String uri = createUri(
				String.format("/api/v1/tmdb/movie/%s/recommendations?page=%s&language=%s", movieTmdbId, page, "pt-Br"));

		try {
			ResponseEntity<MovieSearchResponseDto> responseEntity = template.exchange(uri, HttpMethod.GET, null,
					MovieSearchResponseDto.class);

			MovieSearchResponseDto response = responseEntity.getBody();

			return response.toDomain();
		} catch (Exception e) {
			log.error(e.getMessage());
			throw new RequestRejectedException(e.getMessage());
		}
	}

	@Override
	public MovieSearch moviesSimilar(Long movieTmdbId, Integer page) {
		RestTemplate template = getRestTemplate();
		String uri = createUri(
				String.format("/api/v1/tmdb/movie/%s/similar?page=%s&language=%s", movieTmdbId, page, "pt-Br"));

		try {
			ResponseEntity<MovieSearchResponseDto> responseEntity = template.exchange(uri, HttpMethod.GET, null,
					MovieSearchResponseDto.class);

			MovieSearchResponseDto response = responseEntity.getBody();

			return response.toDomain();
		} catch (Exception e) {
			log.error(e.getMessage());
			throw new RequestRejectedException(e.getMessage());
		}
	}

	@Override
	public MovieSearch discoverMovies(String sortByParam, Integer page, Integer primaryReleaseYear, String withGenres,
			String withoutGenres) {
		RestTemplate template = getRestTemplate();
		StringBuilder uriBuilder = new StringBuilder(
				String.format("/api/v1/tmdb/discover/movie?page=%s&language=%s&include_adult=false&vote_count.gte=300",
						page, "pt-Br"));

		if (sortByParam != null) {
			uriBuilder.append(String.format("&sort_by=%s", sortByParam));
		}

		if (primaryReleaseYear != null) {
			uriBuilder.append(String.format("&primary_release_year=%s", primaryReleaseYear));
		}

		if (withGenres != null) {
			uriBuilder.append(String.format("&with_genres=%s", withGenres));
		}

		if (withoutGenres != null) {
			uriBuilder.append(String.format("&without_genres=%s", withoutGenres));
		}

		String uri = createUri(uriBuilder.toString());

		try {
			ResponseEntity<MovieSearchResponseDto> responseEntity = template.exchange(uri, HttpMethod.GET, null,
					MovieSearchResponseDto.class);

			MovieSearchResponseDto response = responseEntity.getBody();

			return response.toDomain();
		} catch (Exception e) {
			log.error(e.getMessage());
			throw new RequestRejectedException(e.getMessage());
		}
	}

	@Override
	public List<MovieGenre> listGenres() {
		String language = "pt-Br";

		RestTemplate template = getRestTemplate();
		String uri = createUri(String.format("/api/v1/tmdb/genre/movie/list?language=%s", language));

		try {
			ResponseEntity<MovieGenresResponseDto> responseEntity = template.exchange(uri, HttpMethod.GET, null,
					MovieGenresResponseDto.class);

			MovieGenresResponseDto response = responseEntity.getBody();
			List<MovieGenreResponseDto> genres = response.getGenres();

			return genres.stream().map(MovieGenreResponseDto::toDomain).collect(Collectors.toList());
		} catch (Exception e) {
			log.error(e.getMessage());
			throw new RequestRejectedException(e.getMessage());
		}
	}

	private String createUri(String path) {
		String[] schemeHost = URL_KIT.split("://");
		UriComponents uri = UriComponentsBuilder.newInstance()
				.scheme(schemeHost[0])
				.host(schemeHost[1])
				.path(path)
				.build();
		return uri.toString();
	}

	private RestTemplate getRestTemplate() {
		ClientHttpRequestInterceptor interceptor = (request, body, execution) -> {
			request.getHeaders().add("Authorization", String.format("Bearer %s", APIKEY));
			return execution.execute(request, body);
		};

		RestTemplate restTemplate = new RestTemplateBuilder().interceptors(interceptor).build();
		return restTemplate;
	}

}
