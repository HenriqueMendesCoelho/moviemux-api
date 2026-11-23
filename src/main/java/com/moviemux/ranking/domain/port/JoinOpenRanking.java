package com.moviemux.ranking.domain.port;

import java.util.UUID;

public interface JoinOpenRanking {
  /** Atomically recheck current eligibility and join, or leave an active membership unchanged. */
  boolean join(UUID rankingId, UUID actorId);
}
