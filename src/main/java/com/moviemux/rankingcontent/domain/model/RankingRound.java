package com.moviemux.rankingcontent.domain.model;

import java.time.Instant;
import java.util.Objects;

/** Stable numbering is persisted; localized labels are presentation concerns. */
public record RankingRound(RoundIdentity identity, long number, String description, Instant createdAt) {
  public RankingRound {
    Objects.requireNonNull(identity, "identity");
    Objects.requireNonNull(createdAt, "createdAt");
    if (number < 1) throw new IllegalArgumentException("round.number.invalid");
  }
}
