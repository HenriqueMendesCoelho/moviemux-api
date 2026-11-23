package com.moviemux.ranking.domain.usecase;

import com.moviemux.ranking.domain.model.RankingActor;
import java.util.UUID;

public interface JoinOpenRankingUseCase {
  void join(RankingActor actor, UUID rankingId);
}
