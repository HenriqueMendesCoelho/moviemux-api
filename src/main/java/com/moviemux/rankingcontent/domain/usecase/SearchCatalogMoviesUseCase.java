package com.moviemux.rankingcontent.domain.usecase;

import com.moviemux.ranking.domain.model.RankingActor;
import com.moviemux.rankingcontent.domain.model.CatalogMovieSearchPage;
import java.util.UUID;

public interface SearchCatalogMoviesUseCase {
  CatalogMovieSearchPage search(RankingActor actor, UUID rank, String query, int page, String language);
}
