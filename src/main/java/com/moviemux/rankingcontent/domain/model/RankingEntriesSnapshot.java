package com.moviemux.rankingcontent.domain.model;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Authorized actor and scope from the same database snapshot as the metadata rows. */
public record RankingEntriesSnapshot(UUID rankingId, UUID actorUserId, List<RankingMovieEntryView> entries) {
  public RankingEntriesSnapshot { Objects.requireNonNull(rankingId);Objects.requireNonNull(actorUserId);entries=List.copyOf(entries); }
}
