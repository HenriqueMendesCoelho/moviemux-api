package com.moviemux.rankingcontent.domain.usecase.impl;

import com.moviemux.ranking.domain.model.RankingActor;
import com.moviemux.rankingcontent.domain.exception.RankingEntriesDeniedException;
import com.moviemux.rankingcontent.domain.model.LegacyFilmLink;
import com.moviemux.rankingcontent.domain.port.ReadLegacyFilmLink;
import com.moviemux.rankingcontent.domain.usecase.ResolveLegacyFilmLinkUseCase;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class ResolveLegacyFilmLinkUseCaseImpl implements ResolveLegacyFilmLinkUseCase {
  private final ReadLegacyFilmLink links;

  public ResolveLegacyFilmLinkUseCaseImpl(ReadLegacyFilmLink links) {
    this.links = Objects.requireNonNull(links);
  }

  @Override public LegacyFilmLink resolve(RankingActor actor, UUID legacyMovieId) {
    if (actor == null || !actor.accountEnabled() || legacyMovieId == null) throw new RankingEntriesDeniedException();
    return links.find(actor.userId(), legacyMovieId).orElseThrow(RankingEntriesDeniedException::new);
  }
}
