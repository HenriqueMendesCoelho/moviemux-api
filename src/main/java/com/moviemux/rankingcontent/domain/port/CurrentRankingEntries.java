package com.moviemux.rankingcontent.domain.port;

import com.moviemux.rankingcontent.domain.model.RankingEntriesSnapshot;
import java.util.Optional;
import java.util.UUID;

/** One SQL snapshot: authorize active local READ or owner before paging metadata.
 * Empty optional means denied/missing; an authorized empty ranking returns a snapshot with no entries.
 * Order by unsigned UUID, strictly after cursor. maxRows bounds entries, never joined vote/grant rows.
 */
@FunctionalInterface
public interface CurrentRankingEntries {
  Optional<RankingEntriesSnapshot> find(UUID rankingId, UUID actorUserId, UUID after, int maxRows);
}
