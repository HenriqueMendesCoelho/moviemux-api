package com.moviemux.rankingcontent.domain.usecase;

import com.moviemux.ranking.domain.model.RankingActor;
import com.moviemux.rankingcontent.domain.model.AddedCatalogEntry;
import java.util.UUID;

public interface AddExistingCatalogEntryUseCase {
  AddedCatalogEntry execute(RankingActor actor, UUID rankingId, UUID movieId);
}
