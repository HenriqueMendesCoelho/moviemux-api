package com.moviemux.ranking.domain.model;

import java.util.Set;
import java.util.UUID;

/** Current authorized metadata only. Name can be null for unnamed existing rankings. */
public record RankingSummary(UUID rankingId, String name, RankingVisibility visibility,
    RankingAdmission admission, Set<RankingCapability> capabilities) {
  public RankingSummary { capabilities = capabilities == null ? null : Set.copyOf(capabilities); }
}
