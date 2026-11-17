package com.moviemux.ranking.domain.repository;

import java.util.UUID;

/** Protected history must not be destroyed before the explicit identity migration exists. */
@FunctionalInterface
public interface RankingAccountHistory {
  boolean hasProtectedHistory(UUID userId);
}
