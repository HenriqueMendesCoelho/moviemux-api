package com.moviemux.rankingcontent.domain.model;

import java.util.Objects;
import java.util.UUID;

/** Exact legacy film handoff destination, not authority for a subsequent read. */
public record LegacyFilmLink(UUID rankingId, UUID entryId) {
  public LegacyFilmLink {
    Objects.requireNonNull(rankingId);
    Objects.requireNonNull(entryId);
  }
}
