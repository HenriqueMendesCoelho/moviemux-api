package com.moviemux.rankingcontent.domain.port;

import com.moviemux.rankingcontent.domain.model.OwnRoundRatingView;
import java.util.Optional;
import java.util.UUID;

public interface CurrentOwnRoundRating {
  /**
   * Resolve current authorization and the actor's own rating in one database snapshot.
   * Require enabled account and active membership with READ, or active current owner.
   * Archived rankings remain readable; removed entries, absent rating rows and inaccessible scopes
   * return empty. A persisted rating with a null score is present, not empty.
   * Never expose other authors, totals or visibility state, and never mutate or emit events.
   */
  Optional<OwnRoundRatingView> find(UUID rankingId, UUID roundId, UUID actorUserId);
}
