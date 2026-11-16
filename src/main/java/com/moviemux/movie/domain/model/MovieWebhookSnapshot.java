package com.moviemux.movie.domain.model;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public record MovieWebhookSnapshot(
    UUID id,
    String title,
    String imageUrl,
    boolean showNotes,
    String registeredBy,
    OffsetDateTime createdAt,
    List<Rating> ratings) {
  public MovieWebhookSnapshot {
    ratings = ratings == null ? null : List.copyOf(ratings);
  }

  public record Rating(String userName, Integer note) {}
}
