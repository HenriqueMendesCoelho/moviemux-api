package com.moviemux.ranking.domain.port;

import com.moviemux.ranking.domain.model.RankingContextSummary;
import java.util.Optional;
import java.util.UUID;

/**
 * Read only with current private access. Sharing proof is true only for the actual active owner
 * of a live OPEN ranking, derived together with the summary; never infer it from a grant.
 */
public interface CurrentRankingSummary {
  Optional<RankingContextSummary> find(UUID actor, UUID rankingId);
}
