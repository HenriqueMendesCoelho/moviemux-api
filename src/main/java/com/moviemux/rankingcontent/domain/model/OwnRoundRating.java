package com.moviemux.rankingcontent.domain.model;

import java.util.Objects;
import java.util.UUID;

/** Result of a new contextual write, not a reconstitution rule for historical ratings. */
public record OwnRoundRating(UUID roundId, int score) {
  public OwnRoundRating { Objects.requireNonNull(roundId, "roundId"); }
}
