package com.moviemux.ranking.domain.repository;

import com.moviemux.ranking.domain.model.RankingActor;
import java.util.UUID;

public interface CheckRankingCatalogEntryAccess {
  /** Require current active owner or ADD_ENTRY membership; only then return whether archived. */
  boolean check(RankingActor actor, UUID rankingId);
}
