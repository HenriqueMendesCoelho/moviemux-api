package com.moviemux.rankingcontent;

import static org.assertj.core.api.Assertions.*;
import com.moviemux.ranking.domain.model.RankingActor;
import com.moviemux.rankingcontent.domain.exception.*;
import com.moviemux.rankingcontent.domain.model.*;
import com.moviemux.rankingcontent.domain.usecase.impl.ListRankingEntriesUseCaseImpl;
import java.time.OffsetDateTime;
import java.util.*;
import org.junit.jupiter.api.Test;

class ListRankingEntriesUseCaseTest {
  private final UUID rank=UUID.randomUUID(),user=UUID.randomUUID();
  private final RankingActor actor=new RankingActor(user,true);
  private final UUID low=UUID.fromString("7fffffff-ffff-ffff-ffff-ffffffffffff"),high=UUID.fromString("80000000-0000-0000-0000-000000000000");
  private RankingMovieEntryView entry(UUID id){return new RankingMovieEntryView(id,UUID.randomUUID(),null,null,"English","Português",null,null,null,OffsetDateTime.parse("2026-01-01T00:00:00Z"));}
  @Test void returnsEmptyAllowedPageDistinctFromMissingAndKeepsNullMetadata() {
    var empty=new ListRankingEntriesUseCaseImpl((r,a,c,l)->Optional.of(new RankingEntriesSnapshot(rank,user,List.of()))).execute(actor,rank,null,20);
    assertThat(empty.items()).isEmpty();assertThat(empty.nextCursor()).isNull();
    assertThatThrownBy(()->new ListRankingEntriesUseCaseImpl((r,a,c,l)->Optional.empty()).execute(actor,rank,null,20)).isInstanceOf(RankingEntriesDeniedException.class);
    var item=entry(low);
    var page=new ListRankingEntriesUseCaseImpl((r,a,c,l)->Optional.of(new RankingEntriesSnapshot(rank,user,List.of(item)))).execute(actor,rank,null,20);
    assertThat(page.items()).containsExactly(item);assertThat(page.items().get(0).runtime()).isNull();
  }
  @Test void unsignedUuidOrderingAndLookaheadCursorUseLastEmittedEntry() {
    var first=entry(low);var second=entry(high);
    var page=new ListRankingEntriesUseCaseImpl((r,a,c,l)->{
      assertThat(r).isEqualTo(rank);assertThat(a).isEqualTo(user);assertThat(c).isNull();assertThat(l).isEqualTo(2);
      return Optional.of(new RankingEntriesSnapshot(rank,user,List.of(first,second)));
    }).execute(actor,rank,null,1);
    assertThat(page.items()).containsExactly(first);assertThat(page.nextCursor()).isEqualTo(low);
    var tail=new ListRankingEntriesUseCaseImpl((r,a,c,l)->Optional.of(new RankingEntriesSnapshot(rank,user,List.of(second)))).execute(actor,rank,low,100);
    assertThat(tail.items()).containsExactly(second);assertThat(tail.nextCursor()).isNull();
  }
  @Test void invalidActorAndBoundsNeverRead() {
    var useCase=new ListRankingEntriesUseCaseImpl((r,a,c,l)->{throw new AssertionError("Must not query");});
    assertThatThrownBy(()->useCase.execute(null,rank,null,20)).isInstanceOf(RankingEntriesDeniedException.class);
    assertThatThrownBy(()->useCase.execute(new RankingActor(user,false),rank,null,20)).isInstanceOf(RankingEntriesDeniedException.class);
    assertThatThrownBy(()->useCase.execute(actor,null,null,20)).isInstanceOf(RankingEntriesDeniedException.class);
    for(int limit:new int[]{-1,0,101,Integer.MAX_VALUE})assertThatThrownBy(()->useCase.execute(actor,rank,null,limit)).isInstanceOf(InvalidRankingEntriesPageException.class);
  }
  @Test void portScopeActorBoundsAndOrderingViolationsFailClosed() {
    assertThatThrownBy(()->new ListRankingEntriesUseCaseImpl((r,a,c,l)->null).execute(actor,rank,null,20)).isInstanceOf(RankingEntriesDeniedException.class);
    for(var snapshot:List.of(new RankingEntriesSnapshot(UUID.randomUUID(),user,List.of()),new RankingEntriesSnapshot(rank,UUID.randomUUID(),List.of()),
        new RankingEntriesSnapshot(rank,user,List.of(entry(low),entry(low))),new RankingEntriesSnapshot(rank,user,List.of(entry(high),entry(low))),
        new RankingEntriesSnapshot(rank,user,List.of(entry(low),entry(high),entry(UUID.randomUUID()))))) {
      assertThatThrownBy(()->new ListRankingEntriesUseCaseImpl((r,a,c,l)->Optional.of(snapshot)).execute(actor,rank,null,1)).isInstanceOf(RankingEntriesDeniedException.class);
    }
    for(UUID id:List.of(low,UUID.fromString("00000000-0000-0000-0000-000000000001")))
      assertThatThrownBy(()->new ListRankingEntriesUseCaseImpl((r,a,c,l)->Optional.of(new RankingEntriesSnapshot(rank,user,List.of(entry(id))))).execute(actor,rank,low,20)).isInstanceOf(RankingEntriesDeniedException.class);
  }
}
