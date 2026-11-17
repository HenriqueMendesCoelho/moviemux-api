package com.moviemux.rankingcontent.domain.model;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;

/** Catalog metadata only; deliberately excludes authors, votes, round state and aggregates. */
public record RankingMovieEntryView(UUID entryId, UUID movieId, Long tmdbId, String originalTitle,
    String englishTitle, String portugueseTitle, String urlImage, LocalDate releaseDate,
    Integer runtime, OffsetDateTime createdAt) {
  public RankingMovieEntryView { Objects.requireNonNull(entryId);Objects.requireNonNull(movieId);Objects.requireNonNull(createdAt); }
}
