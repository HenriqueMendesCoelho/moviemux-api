package com.moviemux.ranking.domain.model;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** The caller's own current local capabilities; no other membership or account metadata. */
public record RankingAccessView(UUID rankingId, Set<RankingCapability> capabilities) {
  public RankingAccessView {
    Objects.requireNonNull(rankingId, "rankingId");
    capabilities = Set.copyOf(capabilities);
  }
}
