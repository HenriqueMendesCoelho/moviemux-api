package com.moviemux.ranking.domain.port;

import com.moviemux.ranking.domain.model.RankingAccessSnapshot;
import java.util.Optional;
import java.util.UUID;

/**
 * Loads current server-side state for each protected operation. Implementations must not use
 * JWT grants or cross-request authorization caches. Commands must hold/revalidate the same
 * snapshot under a transaction/lock through their write; a standalone check is not a lock.
 */
@FunctionalInterface
public interface CurrentRankingAccess {
  Optional<RankingAccessSnapshot> find(UUID rankingId, UUID actorUserId);
}
