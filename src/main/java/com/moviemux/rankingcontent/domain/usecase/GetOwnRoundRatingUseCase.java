package com.moviemux.rankingcontent.domain.usecase;

import com.moviemux.ranking.domain.model.RankingActor;
import com.moviemux.rankingcontent.domain.model.OwnRoundRatingView;
import java.util.UUID;

public interface GetOwnRoundRatingUseCase {
  OwnRoundRatingView get(RankingActor actor, UUID rankingId, UUID roundId);
}
