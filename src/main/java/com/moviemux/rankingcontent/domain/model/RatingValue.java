package com.moviemux.rankingcontent.domain.model;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;

/** Scoring scale/eligibility is an explicit caller policy, not invented by this value object. */
public record RatingValue(RoundIdentity round, UUID authorId, BigDecimal score) {
  public RatingValue {
    Objects.requireNonNull(round, "round");
    Objects.requireNonNull(authorId, "authorId");
    Objects.requireNonNull(score, "score");
  }
}
