package com.moviemux.rankingcontent.domain.port;

import com.moviemux.rankingcontent.domain.model.EntryRatingAggregateView;
import java.util.Optional;
import java.util.UUID;

public interface CurrentEntryRatingAggregate {
  /**
   * Authorize the enabled actor's active READ membership or current active ownership and
   * aggregate in one snapshot. Missing/inaccessible/removed entries return empty.
   * Archived rankings remain readable. Sum only nonnull scores in revealed=true rounds,
   * preserving historical integer outliers. Votes counts precisely those nonnull scores.
   * With no such scores return total=0, votes=0. Partial means any round revealed IS NOT TRUE,
   * independent of whether it has votes. Never expose hidden counts, authors or write effects.
   */
  Optional<EntryRatingAggregateView> find(UUID rankingId, UUID entryId, UUID actorUserId);
}
