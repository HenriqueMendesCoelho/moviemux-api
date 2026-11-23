package com.moviemux.ranking.domain.port;

import com.moviemux.ranking.domain.model.RankingAccessSnapshot;
import java.util.Optional;
import java.util.UUID;

/** Ranking and membership snapshot including the actor's current enabled account state. */
@FunctionalInterface
public interface CurrentEnabledRankingAccess {
  Optional<RankingAccessSnapshot> findForEnabledAccount(UUID rankingId, UUID actorUserId);
}
