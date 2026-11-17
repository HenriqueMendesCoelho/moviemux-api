package com.moviemux.rankingcontent.domain.model;

import java.util.Objects;
import java.util.UUID;

/** Context is mandatory: neither a catalog ID nor a round number grants access. */
public record RoundIdentity(UUID rankingId, UUID entryId, UUID roundId) {
  public RoundIdentity {
    Objects.requireNonNull(rankingId, "rankingId");
    Objects.requireNonNull(entryId, "entryId");
    Objects.requireNonNull(roundId, "roundId");
  }
}
