package com.moviemux.rankingcontent.domain.port;

import com.moviemux.rankingcontent.domain.model.RoundRatingAggregateView;
import java.util.Optional;
import java.util.UUID;

public interface ReadRoundRatingAggregate {
  /**
   * Authorize current actor and exact active entry/round in one snapshot. Aggregate only
   * revealed scores; never read hidden participation or trigger a reveal.
   */
  Optional<RoundRatingAggregateView> find(UUID rank, UUID entry, UUID round, UUID actor);
}
