package com.moviemux.ranking.domain.port;

import java.util.UUID;

/** Atomically lock ranking then membership, recheck ownership, and deactivate only this actor.
 * Preserve membership, grants and all history. Existing inactive non-owner membership succeeds.
 */
@FunctionalInterface
public interface LeaveOwnRankingMembership {
  Outcome leave(UUID rankingId, UUID actorUserId);
  enum Outcome { LEFT, NOT_FOUND, OWNER_REQUIRES_DESTINATION }
}
