package com.moviemux.ranking.domain.port;

import com.moviemux.ranking.domain.model.RankingEntryRemovalTarget;
import java.util.Optional;
import java.util.UUID;

/** Future persistence must load entry scope and resolve creator account from authoritative state,
 * never client creator IDs or public deleted-author codes. Query must be scoped by both ranking and entry. No persistence adapter/entry endpoint exists yet.
 * A future mutation must lock/revalidate entry and authorization in its own transaction.
 */
@FunctionalInterface
public interface CurrentRankingEntryRemovalTarget {
  Optional<RankingEntryRemovalTarget> find(UUID rankingId, UUID entryId);
}
