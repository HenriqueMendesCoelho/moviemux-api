package com.moviemux.ranking.domain.port;

import com.moviemux.ranking.domain.model.RankingVisibility;
import java.util.UUID;

public interface RankingVisibilitySettings {
  /** Read only for an enabled, active, current owner of a live rank. */
  RankingVisibility current(UUID rankingId, UUID actorId);

  /** Set only visibility, under current authorization. A matching value is a no-op. */
  RankingVisibility set(UUID rankingId, UUID actorId, RankingVisibility visibility);
}
