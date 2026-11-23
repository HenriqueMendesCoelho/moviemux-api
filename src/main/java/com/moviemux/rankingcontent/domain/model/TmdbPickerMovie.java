package com.moviemux.rankingcontent.domain.model;

import java.time.LocalDate;
import java.util.regex.Pattern;

/** Exact film picker projection, with no provider rank or contextual presence data. */
public record TmdbPickerMovie(long tmdbId, String title, String originalTitle, LocalDate releaseDate, String posterPath) {
  private static final Pattern POSTER = Pattern.compile("/[A-Za-z0-9_-]+[.](?:jpg|jpeg|png|webp)");

  public static boolean isSafePosterPath(String path) {
    return path != null && path.length() <= 255 && POSTER.matcher(path).matches();
  }
}
