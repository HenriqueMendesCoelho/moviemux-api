package com.moviemux.rankingcontent.domain.usecase.impl;

import com.moviemux.ranking.domain.model.RankingActor;
import com.moviemux.rankingcontent.domain.exception.RankingEntriesDeniedException;
import com.moviemux.rankingcontent.domain.model.EntryRatingAggregateView;
import com.moviemux.rankingcontent.domain.port.CurrentEntryRatingAggregate;
import com.moviemux.rankingcontent.domain.usecase.GetEntryRatingAggregateUseCase;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly=true, isolation=Isolation.READ_COMMITTED)
public class GetEntryRatingAggregateUseCaseImpl implements GetEntryRatingAggregateUseCase {
  private final CurrentEntryRatingAggregate ratings;
  public GetEntryRatingAggregateUseCaseImpl(CurrentEntryRatingAggregate ratings) {
    this.ratings=Objects.requireNonNull(ratings);
  }
  @Override public EntryRatingAggregateView get(RankingActor actor, UUID rankingId, UUID entryId) {
    if(actor==null || !actor.accountEnabled() || rankingId==null || entryId==null)
      throw new RankingEntriesDeniedException();
    var found=ratings.find(rankingId,entryId,actor.userId());
    if(found==null || found.isEmpty() || !entryId.equals(found.get().entryId()))
      throw new RankingEntriesDeniedException();
    var result=found.get();
    if(result.total()==null || result.votes()==null || result.votes().signum()<0
        || (result.votes().signum()==0 && result.total().signum()!=0))
      throw new RankingEntriesDeniedException();
    // Negative historical totals are valid. Never clamp to the new-write score range.
    return result;
  }
}
