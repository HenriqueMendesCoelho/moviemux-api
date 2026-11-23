package com.moviemux.ranking.domain.usecase;

import com.moviemux.ranking.domain.model.RankingActor;
import com.moviemux.ranking.domain.model.RankingContextSummary;
import java.util.UUID;

public interface GetRankingSummaryUseCase {
  RankingContextSummary get(RankingActor actor, UUID rankingId);
}
