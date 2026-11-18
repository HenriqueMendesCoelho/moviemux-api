package com.moviemux.rankingcontent.domain.port;

import com.moviemux.rankingcontent.domain.model.OwnRoundRating;
import java.util.UUID;

/** Revalidate current actor membership, RATE_OWN/ownership, archive and scoped round under locks.
 * Resolve author from actor identity, never request data. Create must not overwrite; update must
 * not insert. Preserve other/history rows and round revelation; no global notifications/events.
 */
public interface WriteOwnRoundRating {
  OwnRoundRating create(UUID rankingId, UUID roundId, UUID actorUserId, int score);
  OwnRoundRating update(UUID rankingId, UUID roundId, UUID actorUserId, int score);
}
