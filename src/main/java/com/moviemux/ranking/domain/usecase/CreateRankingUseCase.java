package com.moviemux.ranking.domain.usecase;

import com.moviemux.ranking.domain.model.*;

public interface CreateRankingUseCase {
  RankingSummary create(RankingActor actor, String name, RankingVisibility visibility, RankingAdmission admission);
}
