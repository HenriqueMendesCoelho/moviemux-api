package com.moviemux.ranking.domain.model;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Authoritative entry context, not request fields. Empty creator means orphan/unlinked author. */
public record RankingEntryRemovalTarget(UUID entryId, UUID rankingId, Optional<UUID> creatorAccountId) {
  public RankingEntryRemovalTarget {
    Objects.requireNonNull(entryId);Objects.requireNonNull(rankingId);Objects.requireNonNull(creatorAccountId);
  }
}
