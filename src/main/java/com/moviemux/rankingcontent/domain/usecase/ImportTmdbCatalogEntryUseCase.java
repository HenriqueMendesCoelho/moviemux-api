package com.moviemux.rankingcontent.domain.usecase;

import com.moviemux.ranking.domain.model.RankingActor;
import com.moviemux.rankingcontent.domain.model.AddedCatalogEntry;
import java.util.UUID;

public interface ImportTmdbCatalogEntryUseCase {
  long MAX_TMDB_ID = 9_007_199_254_740_991L;

  AddedCatalogEntry execute(RankingActor actor, UUID rankingId, long tmdbId);
}
