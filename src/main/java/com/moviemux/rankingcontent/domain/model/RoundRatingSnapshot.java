package com.moviemux.rankingcontent.domain.model;

import java.util.List;
import java.util.Objects;

/** Internal, authoritative snapshot of one round and its eligible current votes.
 * Not an HTTP DTO: callers must authorize the ranking and resolve revelation on the server.
 */
public record RoundRatingSnapshot(RoundIdentity identity, boolean revealed, List<RatingValue> votes) {
  public RoundRatingSnapshot {
    Objects.requireNonNull(identity, "identity");
    votes = List.copyOf(votes);
    for (RatingValue vote : votes) {
      if (!identity.equals(vote.round()))
        throw new IllegalArgumentException("rating.snapshot.context_mismatch");
    }
  }
}
