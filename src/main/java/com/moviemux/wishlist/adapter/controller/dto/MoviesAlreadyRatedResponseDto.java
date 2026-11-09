package com.moviemux.wishlist.adapter.controller.dto;

import com.moviemux.movie.domain.model.MovieCatalogEntry;
import java.util.List;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
public class MoviesAlreadyRatedResponseDto {

  private List<Long> movieTmdbIds;

  public MoviesAlreadyRatedResponseDto(List<MovieCatalogEntry> movies) {
    movieTmdbIds = movies.stream().map(m -> m.tmdbId()).toList();
  }
}
