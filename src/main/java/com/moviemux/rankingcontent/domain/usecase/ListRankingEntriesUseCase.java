package com.moviemux.rankingcontent.domain.usecase;

import com.moviemux.ranking.domain.model.RankingActor;
import com.moviemux.rankingcontent.domain.model.RankingEntriesPage;
import java.util.UUID;

public interface ListRankingEntriesUseCase {
  RankingEntriesPage execute(RankingActor actor, UUID rankingId, UUID after, int limit);
}
