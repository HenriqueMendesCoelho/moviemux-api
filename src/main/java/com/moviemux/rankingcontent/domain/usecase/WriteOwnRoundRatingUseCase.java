package com.moviemux.rankingcontent.domain.usecase;

import com.moviemux.ranking.domain.model.RankingActor;
import com.moviemux.rankingcontent.domain.model.OwnRoundRating;
import java.util.UUID;

public interface WriteOwnRoundRatingUseCase {
  OwnRoundRating create(RankingActor actor, UUID rankingId, UUID roundId, Integer score);
  OwnRoundRating update(RankingActor actor, UUID rankingId, UUID roundId, Integer score);
}
