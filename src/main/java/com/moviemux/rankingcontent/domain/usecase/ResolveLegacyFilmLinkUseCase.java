package com.moviemux.rankingcontent.domain.usecase;

import com.moviemux.ranking.domain.model.RankingActor;
import com.moviemux.rankingcontent.domain.model.LegacyFilmLink;
import java.util.UUID;

public interface ResolveLegacyFilmLinkUseCase {
  LegacyFilmLink resolve(RankingActor actor, UUID legacyMovieId);
}
