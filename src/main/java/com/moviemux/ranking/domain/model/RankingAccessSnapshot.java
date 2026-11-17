package com.moviemux.ranking.domain.model;

import java.util.Objects;
import java.util.Optional;

/** Read ranking and membership consistently within the protected operation's transaction. */
public record RankingAccessSnapshot(RankingIdentity ranking,
    Optional<RankingMembership> membership) {
  public RankingAccessSnapshot {
    Objects.requireNonNull(ranking, "ranking");
    Objects.requireNonNull(membership, "membership");
    membership.ifPresent(member -> {
      if (!ranking.id().equals(member.rankingId())) {
        throw new IllegalArgumentException("Membership belongs to another ranking");
      }
    });
  }
}
