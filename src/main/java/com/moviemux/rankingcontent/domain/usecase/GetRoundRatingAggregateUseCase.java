package com.moviemux.rankingcontent.domain.usecase;

import com.moviemux.ranking.domain.model.RankingActor;
import com.moviemux.rankingcontent.domain.model.RoundRatingAggregateView;
import java.util.UUID;

public interface GetRoundRatingAggregateUseCase {
  RoundRatingAggregateView get(RankingActor actor, UUID rank, UUID entry, UUID round);
}
