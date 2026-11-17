package com.moviemux.rankingcontent.domain.model;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/** Persisted, contextual disclosure state; never sourced from catalog age or client flags.
 * The legacy profile advances only on a detail read. Its returned state must be
 * persisted by the authorized operation before publishing a revealed projection.
 * Other configurable profiles are deliberately not selected here.
 */
public record RoundDisclosure(RoundIdentity identity, Instant createdAt, boolean revealed) {
  private static final Duration LEGACY_WINDOW = Duration.ofMinutes(30);

  public RoundDisclosure {
    Objects.requireNonNull(identity, "identity");
    Objects.requireNonNull(createdAt, "createdAt");
  }

  /** Pure transition: list reads and rating edits do not invoke or reset this state. */
  public RoundDisclosure onLegacyDetailRead(Instant now) {
    Objects.requireNonNull(now, "now");
    if (revealed || Duration.between(createdAt, now).compareTo(LEGACY_WINDOW) < 0) {
      return this;
    }
    return new RoundDisclosure(identity, createdAt, true);
  }

  public RoundRatingSnapshot snapshot(java.util.List<RatingValue> eligibleVotes) {
    return new RoundRatingSnapshot(identity, revealed, eligibleVotes);
  }
}
