package com.moviemux.ranking.domain.port;

import com.moviemux.ranking.domain.model.RankingAuthorIdentity;
import java.util.UUID;

/** Internal allocation only; callers must supply an existing contextual membership and transaction. */
@FunctionalInterface
public interface AllocateRankingAuthorIdentity {
  RankingAuthorIdentity getOrCreate(UUID rankingId, UUID accountId);
}
