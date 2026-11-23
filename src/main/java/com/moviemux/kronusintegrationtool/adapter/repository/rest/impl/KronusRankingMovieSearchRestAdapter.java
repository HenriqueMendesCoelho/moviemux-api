package com.moviemux.kronusintegrationtool.adapter.repository.rest.impl;

import com.moviemux.kronusintegrationtool.adapter.repository.rest.dto.MovieSearchResponseDto;
import com.moviemux.kronusintegrationtool.adapter.repository.rest.mapper.CatalogResponseMapper;
import com.moviemux.kronusintegrationtool.adapter.repository.rest.mapper.IntegrationFailureMapper;
import com.moviemux.kronusintegrationtool.domain.model.MovieSearch;
import com.moviemux.kronusintegrationtool.domain.repository.KronusRankingMovieSearch;
import java.util.Map;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Repository;
import org.springframework.web.client.RestClient;

@Repository
public class KronusRankingMovieSearchRestAdapter implements KronusRankingMovieSearch {
  private final RestClient kit;

  public KronusRankingMovieSearchRestAdapter(@Qualifier("restClientKitSummary") RestClient kit) {
    this.kit = kit;
  }

  @Override public MovieSearch search(String query, int page, String language) {
    try {
      var body = kit.get().uri(builder -> builder.path("/api/v1/tmdb/search/movie")
            .queryParam("query", "{term}").queryParam("page", "{page}")
            .queryParam("language", "{language}").queryParam("include_adult", "false")
            .build(Map.of("term", query, "page", page, "language", language)))
          .retrieve().body(MovieSearchResponseDto.class);
      return CatalogResponseMapper.search(Objects.requireNonNull(body));
    } catch (Exception failure) {
      // Do not log the term, URI, remote response, or exception. The application
      // boundary converts this typed status into a privacy-safe semantic error.
      throw IntegrationFailureMapper.map(failure);
    }
  }
}
