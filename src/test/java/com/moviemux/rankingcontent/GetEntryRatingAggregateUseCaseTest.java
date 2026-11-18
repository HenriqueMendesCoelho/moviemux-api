package com.moviemux.rankingcontent;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.moviemux.ranking.domain.model.RankingActor;
import com.moviemux.rankingcontent.domain.exception.RankingEntriesDeniedException;
import com.moviemux.rankingcontent.domain.model.EntryRatingAggregateView;
import com.moviemux.rankingcontent.domain.port.CurrentEntryRatingAggregate;
import com.moviemux.rankingcontent.domain.usecase.impl.GetEntryRatingAggregateUseCaseImpl;
import java.math.BigInteger;
import java.util.*;
import org.junit.jupiter.api.Test;

class GetEntryRatingAggregateUseCaseTest {
  private final UUID rank=UUID.randomUUID(),entry=UUID.randomUUID(),user=UUID.randomUUID();
  private final RankingActor actor=new RankingActor(user,true);
  private final CurrentEntryRatingAggregate port=mock(CurrentEntryRatingAggregate.class);
  private final GetEntryRatingAggregateUseCaseImpl useCase=new GetEntryRatingAggregateUseCaseImpl(port);
  @Test void invalidActorAndScopeNeverRead() {
    assertThatThrownBy(()->useCase.get(null,rank,entry)).isInstanceOf(RankingEntriesDeniedException.class);
    assertThatThrownBy(()->useCase.get(new RankingActor(user,false),rank,entry)).isInstanceOf(RankingEntriesDeniedException.class);
    assertThatThrownBy(()->useCase.get(actor,null,entry)).isInstanceOf(RankingEntriesDeniedException.class);
    assertThatThrownBy(()->useCase.get(actor,rank,null)).isInstanceOf(RankingEntriesDeniedException.class);
    verifyNoInteractions(port);
  }
  @Test void exactHistoricalAggregatesAreReturnedWithoutClampingOrOverflow() {
    for(boolean partial:List.of(false,true)) {
      for(var value:List.of(
          new EntryRatingAggregateView(entry,BigInteger.ZERO,BigInteger.ZERO,partial),
          new EntryRatingAggregateView(entry,BigInteger.ZERO,BigInteger.TEN,partial),
          new EntryRatingAggregateView(entry,BigInteger.valueOf(-5),BigInteger.ONE,partial),
          new EntryRatingAggregateView(entry,BigInteger.ONE.shiftLeft(200),BigInteger.ONE.shiftLeft(180),partial))) {
        when(port.find(rank,entry,user)).thenReturn(Optional.of(value));
        assertThat(useCase.get(actor,rank,entry)).isSameAs(value);
      }
    }
    verify(port,times(8)).find(rank,entry,user);verifyNoMoreInteractions(port);
  }
  @Test void invalidPortResultsAreDeniedWithoutLeakingDetails() {
    when(port.find(rank,entry,user)).thenReturn(null);
    assertDenied();
    when(port.find(rank,entry,user)).thenReturn(Optional.empty());
    assertDenied();
    for(var value:List.of(
        new EntryRatingAggregateView(null,BigInteger.ZERO,BigInteger.ZERO,false),
        new EntryRatingAggregateView(UUID.randomUUID(),BigInteger.ZERO,BigInteger.ZERO,false),
        new EntryRatingAggregateView(entry,null,BigInteger.ZERO,false),
        new EntryRatingAggregateView(entry,BigInteger.ZERO,null,false),
        new EntryRatingAggregateView(entry,BigInteger.ZERO,BigInteger.ONE.negate(),false),
        new EntryRatingAggregateView(entry,BigInteger.ONE,BigInteger.ZERO,false),
        new EntryRatingAggregateView(entry,BigInteger.ONE.negate(),BigInteger.ZERO,true))) {
      when(port.find(rank,entry,user)).thenReturn(Optional.of(value));assertDenied();
    }
  }
  @Test void failureIsPropagatedWithoutRetryOrAlternativeScope() {
    var denied=new RankingEntriesDeniedException();
    when(port.find(rank,entry,user)).thenThrow(denied);
    assertThatThrownBy(()->useCase.get(actor,rank,entry)).isSameAs(denied);
    verify(port).find(rank,entry,user);verifyNoMoreInteractions(port);
    assertThatNullPointerException().isThrownBy(()->new GetEntryRatingAggregateUseCaseImpl(null));
  }
  private void assertDenied(){
    assertThatThrownBy(()->useCase.get(actor,rank,entry)).isInstanceOf(RankingEntriesDeniedException.class);
  }
}
