package com.moviemux.movie.domain.model;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Creation intent for the independent instance catalog, not a board/collection mutation. */
public final class ManualCanonicalMovie {
  public static final int MAX_GENRES = 32;
  public static final long MAX_SAFE_ID = 9_007_199_254_740_991L;
  public static final short FINGERPRINT_VERSION = 1;

  private ManualCanonicalMovie() {}

  public record Admin(UUID userId, boolean enabled) {}

  public record Command(UUID commandId, String portugueseTitle, String englishTitle, String originalTitle,
      String director, String description, LocalDate releaseDate, Integer runtime, List<Long> genreIds) {
    @Override public String toString() { return "ManualCanonicalMovie.Command"; }
  }

  public record Result(UUID movieId, boolean created) {}
}
