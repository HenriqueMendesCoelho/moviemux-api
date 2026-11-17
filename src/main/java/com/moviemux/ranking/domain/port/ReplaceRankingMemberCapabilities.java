package com.moviemux.ranking.domain.port;

import com.moviemux.ranking.domain.model.RankingCapability;
import java.util.Set;
import java.util.UUID;

/** Replace grants atomically using current owner and active membership under transaction locks.
 * No delegated/global administration or changes to membership, ownership or historical data.
 */
@FunctionalInterface
public interface ReplaceRankingMemberCapabilities {
  Outcome replace(UUID rankingId, UUID actorUserId, UUID memberUserId, Set<RankingCapability> capabilities);
  enum Outcome { UPDATED, NOT_FOUND, OWNER_CAPABILITIES_IMMUTABLE }
}
