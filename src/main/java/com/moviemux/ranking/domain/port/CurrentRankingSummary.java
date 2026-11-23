package com.moviemux.ranking.domain.port;

import com.moviemux.ranking.domain.model.RankingSummary;
import java.util.Optional;
import java.util.UUID;

/** Read one contextual summary only if its actor currently has private directory access. */
public interface CurrentRankingSummary {
  Optional<RankingSummary> find(UUID actor, UUID rankingId);
}
