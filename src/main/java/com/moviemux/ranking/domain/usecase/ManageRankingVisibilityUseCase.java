package com.moviemux.ranking.domain.usecase;

import com.moviemux.ranking.domain.model.RankingActor;
import com.moviemux.ranking.domain.model.RankingVisibility;
import java.util.UUID;

public interface ManageRankingVisibilityUseCase {
  RankingVisibility current(RankingActor actor, UUID rankingId);

  RankingVisibility set(RankingActor actor, UUID rankingId, RankingVisibility visibility);
}
