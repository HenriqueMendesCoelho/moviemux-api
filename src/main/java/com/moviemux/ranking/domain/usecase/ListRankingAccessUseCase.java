package com.moviemux.ranking.domain.usecase;

import com.moviemux.ranking.domain.model.RankingAccessPage;
import com.moviemux.ranking.domain.model.RankingActor;
import java.util.UUID;

public interface ListRankingAccessUseCase {
  RankingAccessPage execute(RankingActor actor, UUID after, int limit);
}
