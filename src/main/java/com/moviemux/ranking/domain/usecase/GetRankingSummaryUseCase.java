package com.moviemux.ranking.domain.usecase;

import com.moviemux.ranking.domain.model.RankingActor;
import com.moviemux.ranking.domain.model.RankingSummary;
import java.util.UUID;

public interface GetRankingSummaryUseCase {
  RankingSummary get(RankingActor actor, UUID rankingId);
}
