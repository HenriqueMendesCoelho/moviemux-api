package com.moviemux.rankingcontent.domain.usecase.impl;

import com.moviemux.ranking.domain.model.RankingActor;
import com.moviemux.rankingcontent.domain.exception.*;
import com.moviemux.rankingcontent.domain.model.OwnRoundRating;
import com.moviemux.rankingcontent.domain.port.WriteOwnRoundRating;
import com.moviemux.rankingcontent.domain.usecase.WriteOwnRoundRatingUseCase;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(isolation=Isolation.READ_COMMITTED)
public class WriteOwnRoundRatingUseCaseImpl implements WriteOwnRoundRatingUseCase {
  private final WriteOwnRoundRating ratings;
  public WriteOwnRoundRatingUseCaseImpl(WriteOwnRoundRating ratings){this.ratings=Objects.requireNonNull(ratings);}
  @Override public OwnRoundRating create(RankingActor actor,UUID rankingId,UUID roundId,Integer score){return write(actor,rankingId,roundId,score,true);}
  @Override public OwnRoundRating update(RankingActor actor,UUID rankingId,UUID roundId,Integer score){return write(actor,rankingId,roundId,score,false);}
  private OwnRoundRating write(RankingActor actor,UUID rankingId,UUID roundId,Integer score,boolean create) {
    if(actor==null || !actor.accountEnabled() || rankingId==null || roundId==null)throw new RankingEntriesDeniedException();
    // This is a new-write boundary only: existing nullable/out-of-range history is not rewritten.
    if(score==null || score<0 || score>10)throw new InvalidOwnRoundRatingException();
    var result=create?ratings.create(rankingId,roundId,actor.userId(),score):ratings.update(rankingId,roundId,actor.userId(),score);
    if(result==null || !roundId.equals(result.roundId()) || result.score()!=score)throw new RankingEntriesDeniedException();
    return result;
  }
}
