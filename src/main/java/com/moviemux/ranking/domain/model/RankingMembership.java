package com.moviemux.ranking.domain.model;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** An immutable current authorization projection, not the membership lifecycle schema. */
public record RankingMembership(UUID rankingId, UUID userId, boolean active,
    Set<RankingCapability> capabilities) {
  public RankingMembership {
    Objects.requireNonNull(rankingId, "rankingId");
    Objects.requireNonNull(userId, "userId");
    capabilities = Set.copyOf(Objects.requireNonNull(capabilities, "capabilities"));
  }

  public static RankingMembership newlyAdmitted(UUID rankingId, UUID userId) {
    return new RankingMembership(rankingId, userId, true,
        Set.of(RankingCapability.READ, RankingCapability.RATE_OWN));
  }
}
