package com.moviemux.rankingcontent.domain.usecase.impl;

import com.moviemux.ranking.domain.model.RankingActor;
import com.moviemux.rankingcontent.domain.exception.RankingEntriesDeniedException;
import com.moviemux.rankingcontent.domain.model.OwnRoundRatingView;
import com.moviemux.rankingcontent.domain.port.CurrentOwnRoundRating;
import com.moviemux.rankingcontent.domain.usecase.GetOwnRoundRatingUseCase;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly=true, isolation=Isolation.READ_COMMITTED)
public class GetOwnRoundRatingUseCaseImpl implements GetOwnRoundRatingUseCase {
  private final CurrentOwnRoundRating ratings;
  public GetOwnRoundRatingUseCaseImpl(CurrentOwnRoundRating ratings) {
    this.ratings=Objects.requireNonNull(ratings);
  }
  @Override public OwnRoundRatingView get(RankingActor actor, UUID rankingId, UUID roundId) {
    if(actor==null || !actor.accountEnabled() || rankingId==null || roundId==null)
      throw new RankingEntriesDeniedException();
    var found=ratings.find(rankingId,roundId,actor.userId());
    if(found==null || found.isEmpty() || !roundId.equals(found.get().roundId()))
      throw new RankingEntriesDeniedException();
    // Historical values are read verbatim; new-write score validation must not be applied here.
    return found.get();
  }
}
