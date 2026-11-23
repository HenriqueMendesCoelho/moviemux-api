package com.moviemux.rankingcontent.domain.usecase;

import com.moviemux.ranking.domain.model.RankingActor;
import com.moviemux.rankingcontent.domain.model.RecoverableEntriesPage;
import com.moviemux.rankingcontent.domain.model.RestoredEntry;
import java.util.UUID;

public interface ManageRecoverableEntriesUseCase {
  int DEFAULT_LIMIT = 20;
  int MAX_LIMIT = 50;

  RecoverableEntriesPage list(RankingActor actor, UUID rankingId, UUID after, int limit);

  RestoredEntry restoreCurrent(RankingActor actor, UUID rankingId, UUID entryId, long expectedVersion);
}
