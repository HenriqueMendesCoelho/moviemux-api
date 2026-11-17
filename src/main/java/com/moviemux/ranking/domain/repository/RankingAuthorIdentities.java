package com.moviemux.ranking.domain.repository;

import com.moviemux.ranking.domain.model.RankingAuthorIdentity;
import java.util.UUID;

/** Public module contract for authorized contextual writes, inside an existing READ_COMMITTED transaction.
 * Allocation is not authorization: callers must check current actor, membership and write capability.
 */
@FunctionalInterface
public interface RankingAuthorIdentities {
  RankingAuthorIdentity getOrCreate(UUID rankingId, UUID accountId);
}
