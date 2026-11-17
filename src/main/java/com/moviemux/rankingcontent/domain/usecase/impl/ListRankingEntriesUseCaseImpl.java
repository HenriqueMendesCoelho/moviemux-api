package com.moviemux.rankingcontent.domain.usecase.impl;

import com.moviemux.ranking.domain.model.RankingActor;
import com.moviemux.rankingcontent.domain.exception.*;
import com.moviemux.rankingcontent.domain.model.RankingEntriesPage;
import com.moviemux.rankingcontent.domain.port.CurrentRankingEntries;
import com.moviemux.rankingcontent.domain.usecase.ListRankingEntriesUseCase;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly=true)
public class ListRankingEntriesUseCaseImpl implements ListRankingEntriesUseCase {
  private final CurrentRankingEntries entries;
  public ListRankingEntriesUseCaseImpl(CurrentRankingEntries entries) { this.entries=Objects.requireNonNull(entries); }
  @Override public RankingEntriesPage execute(RankingActor actor, UUID rankingId, UUID after, int limit) {
    if(actor==null || !actor.accountEnabled() || rankingId==null)throw new RankingEntriesDeniedException();
    if(limit<1 || limit>100)throw new InvalidRankingEntriesPageException();
    var result=entries.find(rankingId,actor.userId(),after,limit+1);
    if(result==null)throw new RankingEntriesDeniedException();
    var snapshot=result.orElseThrow(RankingEntriesDeniedException::new);
    if(!rankingId.equals(snapshot.rankingId()) || !actor.userId().equals(snapshot.actorUserId()) || snapshot.entries().size()>limit+1)
      throw new RankingEntriesDeniedException();
    UUID previous=after;
    for(var entry:snapshot.entries()) {
      if(previous!=null && previous.toString().compareTo(entry.entryId().toString())>=0)throw new RankingEntriesDeniedException();
      previous=entry.entryId();
    }
    var items=snapshot.entries().subList(0,Math.min(limit,snapshot.entries().size()));
    return new RankingEntriesPage(items,snapshot.entries().size()>limit?items.get(items.size()-1).entryId():null);
  }
}
