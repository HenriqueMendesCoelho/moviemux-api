package com.moviemux.rankingcontent.domain.usecase.impl;

import com.moviemux.ranking.domain.model.RankingActor;
import com.moviemux.rankingcontent.domain.exception.RankingEntriesDeniedException;
import com.moviemux.rankingcontent.domain.exception.InvalidCatalogEntryException;
import com.moviemux.rankingcontent.domain.model.AddedCatalogEntry;
import com.moviemux.rankingcontent.domain.port.AddExistingCatalogEntry;
import com.moviemux.rankingcontent.domain.usecase.AddExistingCatalogEntryUseCase;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;

@Service
@Transactional(isolation=Isolation.READ_COMMITTED)
public class AddExistingCatalogEntryUseCaseImpl implements AddExistingCatalogEntryUseCase {
  private final AddExistingCatalogEntry entries;
  public AddExistingCatalogEntryUseCaseImpl(AddExistingCatalogEntry entries) { this.entries=Objects.requireNonNull(entries); }
  @Override public AddedCatalogEntry execute(RankingActor actor, UUID rankingId, UUID movieId) {
    if(actor==null || !actor.accountEnabled() || rankingId==null) throw new RankingEntriesDeniedException();
    if(movieId==null) throw new InvalidCatalogEntryException();
    var result=entries.write(rankingId,actor.userId(),movieId);
    if(result==null) throw new RankingEntriesDeniedException();
    return result;
  }
}
