package com.moviemux.rankingcontent.domain.model;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record CatalogMovieSearchPage(List<Item> items, int page, boolean hasMore) {
  public CatalogMovieSearchPage { items = List.copyOf(items); }

  public record Item(UUID movieId, String title, LocalDate releaseDate) {
    public Item {
      Objects.requireNonNull(movieId);
      if (title == null || title.codePoints().allMatch(cp -> Character.isWhitespace(cp) || Character.isSpaceChar(cp)))
        throw new IllegalArgumentException("Missing catalog movie title");
    }
  }
}
