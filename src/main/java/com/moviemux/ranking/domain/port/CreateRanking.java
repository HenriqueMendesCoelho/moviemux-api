package com.moviemux.ranking.domain.port;

import com.moviemux.ranking.domain.model.*;
import java.util.UUID;

public interface CreateRanking {
  /** Atomically check the explicit owner quota and create ranking plus active owner membership. */
  RankingSummary create(UUID actor, String name, RankingVisibility visibility,
      RankingAdmission admission, RankingCreationQuota quota);
}
