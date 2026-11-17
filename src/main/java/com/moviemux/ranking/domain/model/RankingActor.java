package com.moviemux.ranking.domain.model;

import java.util.Objects;
import java.util.UUID;

/** Construct only from a validated principal and current account state, never a request body. */
public record RankingActor(UUID userId, boolean accountEnabled) {
  public RankingActor { Objects.requireNonNull(userId, "userId"); }
}
