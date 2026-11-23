package com.moviemux.ranking.domain.usecase.impl;

import com.moviemux.ranking.domain.model.*;
import com.moviemux.ranking.domain.port.ListRankingSummaries;
import com.moviemux.ranking.domain.exception.*;
import com.moviemux.ranking.domain.usecase.ListRankingSummariesUseCase;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
@Transactional(readOnly=true, isolation=Isolation.READ_COMMITTED)
public class ListRankingSummariesUseCaseImpl implements ListRankingSummariesUseCase {
  private final ListRankingSummaries rankings;
  public ListRankingSummariesUseCaseImpl(ListRankingSummaries rankings) {
    this.rankings=Objects.requireNonNull(rankings);
  }
  @Override public RankingSummaryPage list(RankingActor actor, UUID after, int limit) {
    if(actor==null || !actor.accountEnabled()) throw new RankingAccessDeniedException();
    if(limit<1 || limit>100) throw new InvalidRankingAccessPageException();
    var found=rankings.find(actor.userId(),after,limit+1);
    if(found==null || found.size()>limit+1) throw new RankingAccessDeniedException();
    UUID previous=after;
    for(var item:found) {
      if(item==null || item.rankingId()==null || item.visibility()==null || item.admission()==null
          || item.capabilities()==null || !item.capabilities().contains(RankingCapability.READ)
          || (previous!=null && previous.toString().compareTo(item.rankingId().toString())>=0))
        throw new RankingAccessDeniedException();
      previous=item.rankingId();
    }
    var items=found.subList(0,Math.min(limit,found.size()));
    return new RankingSummaryPage(items,found.size()>limit?items.get(items.size()-1).rankingId():null);
  }
}
