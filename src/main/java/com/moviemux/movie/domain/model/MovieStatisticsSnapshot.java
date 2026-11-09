package com.moviemux.movie.domain.model;

import java.time.OffsetDateTime;

/** Public values used by statistics without exposing a persistence entity. */
public final class MovieStatisticsSnapshot {
  private MovieStatisticsSnapshot() {}

  public record Film(OffsetDateTime createdAt, Integer runtime) {}

  public record Genre(String name, int numberOfMovies) {}
}
