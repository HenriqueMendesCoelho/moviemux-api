package com.moviemux.ranking.domain.usecase;

import com.moviemux.ranking.domain.model.RankingAccessView;
import com.moviemux.ranking.domain.model.RankingActor;
import java.util.UUID;

public interface GetRankingAccessUseCase {
  RankingAccessView execute(RankingActor actor, UUID rankingId);
}
