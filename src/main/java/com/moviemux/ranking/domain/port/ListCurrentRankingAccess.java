package com.moviemux.ranking.domain.port;

import com.moviemux.ranking.domain.model.RankingAccessSnapshot;
import java.util.List;
import java.util.UUID;

/** Current server state in one snapshot, filtered before paging; no JWT or cached grants.
 * Results must belong to actor, be active and readable, unique, in PostgreSQL UUID order,
 * strictly after the cursor, and contain all grants. maxRows is a technical query bound.
 */
@FunctionalInterface
public interface ListCurrentRankingAccess {
  List<RankingAccessSnapshot> findAccessible(UUID actorUserId, UUID after, int maxRows);
}
