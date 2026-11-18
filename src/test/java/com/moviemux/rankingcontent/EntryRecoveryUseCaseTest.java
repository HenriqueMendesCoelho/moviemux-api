package com.moviemux.rankingcontent;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.moviemux.ranking.domain.model.RankingActor;
import com.moviemux.rankingcontent.domain.exception.*;
import com.moviemux.rankingcontent.domain.model.EntryRecoveryState;
import com.moviemux.rankingcontent.domain.port.EntryRecovery;
import com.moviemux.rankingcontent.domain.usecase.impl.EntryRecoveryUseCaseImpl;
import java.time.OffsetDateTime;
import java.util.*;
import org.junit.jupiter.api.Test;
class EntryRecoveryUseCaseTest {
  private final UUID rank=UUID.randomUUID(),entry=UUID.randomUUID(),user=UUID.randomUUID(),token=UUID.randomUUID();
  private final RankingActor actor=new RankingActor(user,true);
  private final OffsetDateTime removed=OffsetDateTime.parse("2026-01-01T00:00:00Z");
  private EntryRecoveryState active(long version){return new EntryRecoveryState(entry,version,null,null,null);}
  private EntryRecoveryState deleted(long version){return new EntryRecoveryState(entry,version,removed,removed.plusHours(24),token);}
  @Test void validatesAllActorAndScopeInputsBeforePorts() {
    var port=mock(EntryRecovery.class);var useCase=new EntryRecoveryUseCaseImpl(port);
    for(RankingActor invalid:Arrays.asList(null,new RankingActor(user,false))){
      assertThatThrownBy(()->useCase.preview(invalid,rank,entry)).isInstanceOf(RankingEntriesDeniedException.class);
      assertThatThrownBy(()->useCase.remove(invalid,rank,entry,0L,true)).isInstanceOf(RankingEntriesDeniedException.class);
      assertThatThrownBy(()->useCase.restore(invalid,rank,entry,token)).isInstanceOf(RankingEntriesDeniedException.class);
    }
    assertThatThrownBy(()->useCase.preview(actor,null,entry)).isInstanceOf(RankingEntriesDeniedException.class);
    assertThatThrownBy(()->useCase.preview(actor,rank,null)).isInstanceOf(RankingEntriesDeniedException.class);
    verifyNoInteractions(port);
  }
  @Test void validatesConfirmationVersionAndRestoreTokenBeforePorts() {
    var port=mock(EntryRecovery.class);var useCase=new EntryRecoveryUseCaseImpl(port);
    for(Long version:Arrays.asList(null,-1L,Long.MAX_VALUE))assertThatThrownBy(()->useCase.remove(actor,rank,entry,version,true)).isInstanceOf(InvalidEntryRecoveryException.class);
    assertThatThrownBy(()->useCase.remove(actor,rank,entry,0L,false)).isInstanceOf(InvalidEntryRecoveryException.class);
    assertThatThrownBy(()->useCase.restore(actor,rank,entry,null)).isInstanceOf(InvalidEntryRecoveryException.class);
    verifyNoInteractions(port);
  }
  @Test void forwardsOnlyAuthenticatedIdentityAndDoesNotRetryMutations() {
    var port=mock(EntryRecovery.class);var useCase=new EntryRecoveryUseCaseImpl(port);
    when(port.preview(rank,entry,user)).thenReturn(active(0));when(port.remove(rank,entry,user,0)).thenReturn(deleted(1));when(port.restore(rank,entry,user,token)).thenReturn(active(2));
    assertThat(useCase.preview(actor,rank,entry)).isEqualTo(active(0));
    assertThat(useCase.remove(actor,rank,entry,0L,true)).isEqualTo(deleted(1));
    assertThat(useCase.restore(actor,rank,entry,token)).isEqualTo(active(2));
    verify(port).preview(rank,entry,user);verify(port).remove(rank,entry,user,0);verify(port).restore(rank,entry,user,token);verifyNoMoreInteractions(port);
  }
  @Test void malformedPortStateFailsClosedWithoutReturningForeignOrPartialData() {
    var invalid=Arrays.asList(null,new EntryRecoveryState(UUID.randomUUID(),0,null,null,null),active(-1),
        new EntryRecoveryState(entry,0,null,removed,null),new EntryRecoveryState(entry,0,null,null,token),
        new EntryRecoveryState(entry,1,removed,null,token),new EntryRecoveryState(entry,1,removed,removed.plusHours(24),null),
        new EntryRecoveryState(entry,1,removed,removed.plusHours(25),token));
    for(var state:invalid){var port=mock(EntryRecovery.class);when(port.preview(rank,entry,user)).thenReturn(state);
      assertThatThrownBy(()->new EntryRecoveryUseCaseImpl(port).preview(actor,rank,entry)).isInstanceOf(RankingEntriesDeniedException.class);}
  }
  @Test void successfulMutationsMustActuallyReflectRequestedTransition() {
    for(var state:List.of(active(1),deleted(2))){var port=mock(EntryRecovery.class);when(port.remove(rank,entry,user,0)).thenReturn(state);
      assertThatThrownBy(()->new EntryRecoveryUseCaseImpl(port).remove(actor,rank,entry,0L,true)).isInstanceOf(RankingEntriesDeniedException.class);}
    for(var state:List.of(active(0),deleted(1))){var port=mock(EntryRecovery.class);when(port.restore(rank,entry,user,token)).thenReturn(state);
      assertThatThrownBy(()->new EntryRecoveryUseCaseImpl(port).restore(actor,rank,entry,token)).isInstanceOf(RankingEntriesDeniedException.class);}
  }
  @Test void staleExpiredAndArchiveErrorsAreNotRetriedOrConvertedIntoSuccess(){
    for(RuntimeException failure:List.of(new StaleEntryRecoveryException(),new ExpiredEntryRecoveryException(),new ArchivedEntryRecoveryException())){
      var port=mock(EntryRecovery.class);when(port.restore(rank,entry,user,token)).thenThrow(failure);
      assertThatThrownBy(()->new EntryRecoveryUseCaseImpl(port).restore(actor,rank,entry,token)).isSameAs(failure);
      verify(port).restore(rank,entry,user,token);verifyNoMoreInteractions(port);
    }
  }
}
