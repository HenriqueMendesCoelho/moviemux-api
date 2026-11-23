package com.moviemux.rankingcontent;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.moviemux.ranking.domain.model.RankingActor;
import com.moviemux.rankingcontent.domain.exception.*;
import com.moviemux.rankingcontent.domain.model.*;
import com.moviemux.rankingcontent.domain.port.ListRecoverableEntries;
import com.moviemux.rankingcontent.domain.port.RestoreCurrentEntry;
import com.moviemux.rankingcontent.domain.usecase.impl.ManageRecoverableEntriesUseCaseImpl;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ManageRecoverableEntriesUseCaseTest {
  private static final UUID USER=new UUID(0,1), RANK=new UUID(0,2);
  private static final UUID FIRST=UUID.fromString("7fffffff-ffff-ffff-ffff-ffffffffffff"),
      NEXT=UUID.fromString("80000000-0000-0000-0000-000000000000");
  private static final RankingActor ACTOR=new RankingActor(USER,true);
  private static final OffsetDateTime REMOVED=OffsetDateTime.parse("2026-12-01T09:00:00-03:00");
  private final ListRecoverableEntries directory=mock(ListRecoverableEntries.class);
  private final RestoreCurrentEntry restore=mock(RestoreCurrentEntry.class);
  private final ManageRecoverableEntriesUseCaseImpl useCase=new ManageRecoverableEntriesUseCaseImpl(directory,restore);

  @Test void invalidActorScopePageAndVersionNeverUsePersistence(){
    for(var actor:Arrays.asList(null,new RankingActor(USER,false))){
      assertThatThrownBy(()->useCase.list(actor,RANK,null,20)).isInstanceOf(RankingEntriesDeniedException.class);
      assertThatThrownBy(()->useCase.restoreCurrent(actor,RANK,FIRST,1)).isInstanceOf(RankingEntriesDeniedException.class);
    }
    assertThatThrownBy(()->useCase.list(ACTOR,null,null,20)).isInstanceOf(RankingEntriesDeniedException.class);
    assertThatThrownBy(()->useCase.restoreCurrent(ACTOR,RANK,null,1)).isInstanceOf(RankingEntriesDeniedException.class);
    for(int limit:new int[]{0,-1,51,Integer.MAX_VALUE})
      assertThatThrownBy(()->useCase.list(ACTOR,RANK,null,limit)).isInstanceOf(InvalidEntryRecoveryException.class);
    for(long version:new long[]{-1,Long.MAX_VALUE})
      assertThatThrownBy(()->useCase.restoreCurrent(ACTOR,RANK,FIRST,version)).isInstanceOf(InvalidEntryRecoveryException.class);
    verifyNoInteractions(directory,restore);
  }

  @Test void listPaginatesInUnsignedUuidOrderAndPreservesNullTitle(){
    var first=row(FIRST);
    var next=row(NEXT);
    when(directory.find(USER,RANK,null,2)).thenReturn(List.of(first,next));
    when(directory.find(USER,RANK,FIRST,2)).thenReturn(List.of(next));
    when(directory.find(USER,RANK,NEXT,51)).thenReturn(List.of());
    var page=useCase.list(ACTOR,RANK,null,1);
    assertThat(page.items()).containsExactly(first);
    assertThat(page.items().getFirst().title()).isNull();
    assertThat(page.nextCursor()).isEqualTo(FIRST);
    assertThatThrownBy(()->page.items().clear()).isInstanceOf(UnsupportedOperationException.class);
    assertThat(useCase.list(ACTOR,RANK,FIRST,1)).isEqualTo(new RecoverableEntriesPage(List.of(next),null));
    assertThat(useCase.list(ACTOR,RANK,NEXT,50)).isEqualTo(new RecoverableEntriesPage(List.of(),null));
    verifyNoInteractions(restore);
  }

  @Test void malformedRowsAndLookaheadFailClosed(){
    List<List<RecoverableEntry>> invalid=Arrays.asList(null,List.of(row(FIRST),row(NEXT),row(new UUID(-1,-1))),
        Arrays.asList(row(FIRST),null),List.of(row(null)),
        List.of(new RecoverableEntry(FIRST,null,REMOVED,REMOVED.plusHours(24),-1)),
        List.of(new RecoverableEntry(FIRST,null,null,REMOVED.plusHours(24),0)),
        List.of(new RecoverableEntry(FIRST,null,REMOVED,null,0)),
        List.of(new RecoverableEntry(FIRST,null,REMOVED,REMOVED.plusHours(24).minusNanos(1),0)),
        List.of(row(FIRST),row(FIRST)),List.of(row(NEXT),row(FIRST)));
    for(var value:invalid){
      when(directory.find(USER,RANK,null,2)).thenReturn(value);
      assertThatThrownBy(()->useCase.list(ACTOR,RANK,null,1)).isInstanceOf(RankingEntriesDeniedException.class);
    }
    when(directory.find(USER,RANK,FIRST,2)).thenReturn(List.of(row(FIRST)));
    assertThatThrownBy(()->useCase.list(ACTOR,RANK,FIRST,1)).isInstanceOf(RankingEntriesDeniedException.class);
  }

  @Test void restorePassesExactVersionAndRequiresExactIncrement(){
    long expected=Long.MAX_VALUE-1;
    var exact=new RestoredEntry(FIRST,Long.MAX_VALUE);
    when(restore.restoreCurrent(RANK,FIRST,USER,expected)).thenReturn(exact,null,
        new RestoredEntry(NEXT,Long.MAX_VALUE),new RestoredEntry(FIRST,expected));
    assertThat(useCase.restoreCurrent(ACTOR,RANK,FIRST,expected)).isEqualTo(exact);
    for(int i=0;i<3;i++)
      assertThatThrownBy(()->useCase.restoreCurrent(ACTOR,RANK,FIRST,expected)).isInstanceOf(RankingEntriesDeniedException.class);
    verify(restore,times(4)).restoreCurrent(RANK,FIRST,USER,expected);verifyNoMoreInteractions(restore);
    verifyNoInteractions(directory);
  }

  private static RecoverableEntry row(UUID id){
    return new RecoverableEntry(id,null,REMOVED,REMOVED.plusHours(24).withOffsetSameInstant(java.time.ZoneOffset.UTC),1);
  }
}
