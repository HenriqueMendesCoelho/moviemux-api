package com.moviemux.ranking.domain.model;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Collection-local selector and intentional display-only profile data, never an account/author ID. */
public record RosterMember(UUID membershipId, String displayName, Set<RankingCapability> capabilities) {
  public RosterMember {
    Objects.requireNonNull(membershipId, "membershipId");
    capabilities = Set.copyOf(capabilities);
  }
}
