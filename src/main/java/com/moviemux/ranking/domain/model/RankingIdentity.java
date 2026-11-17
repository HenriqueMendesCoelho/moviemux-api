package com.moviemux.ranking.domain.model;

import java.util.Objects;
import java.util.UUID;

/** Visibility does not imply admission or membership. */
public record RankingIdentity(UUID id, UUID ownerUserId, RankingVisibility visibility,
    RankingAdmission admission) {
  public RankingIdentity {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(ownerUserId, "ownerUserId");
    Objects.requireNonNull(visibility, "visibility");
    Objects.requireNonNull(admission, "admission");
  }
}
