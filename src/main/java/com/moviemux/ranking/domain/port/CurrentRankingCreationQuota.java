package com.moviemux.ranking.domain.port;

import com.moviemux.ranking.domain.model.RankingCreationQuota;
import java.util.Optional;

/** Missing or invalid deployment configuration must not silently select a default. */
public interface CurrentRankingCreationQuota {
  Optional<RankingCreationQuota> find();
}
