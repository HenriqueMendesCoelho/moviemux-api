package com.moviemux.rankingcontent;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.moviemux.ranking.domain.model.RankingActor;
import com.moviemux.rankingcontent.domain.exception.RankingEntriesDeniedException;
import com.moviemux.rankingcontent.domain.model.OwnRoundRatingView;
import com.moviemux.rankingcontent.domain.port.CurrentOwnRoundRating;
import com.moviemux.rankingcontent.domain.usecase.impl.GetOwnRoundRatingUseCaseImpl;
import java.util.*;
import org.junit.jupiter.api.Test;

class GetOwnRoundRatingUseCaseTest {
  private final UUID rank=UUID.randomUUID(),round=UUID.randomUUID(),user=UUID.randomUUID();
  private final RankingActor actor=new RankingActor(user,true);
  private final CurrentOwnRoundRating port=mock(CurrentOwnRoundRating.class);
  private final GetOwnRoundRatingUseCaseImpl useCase=new GetOwnRoundRatingUseCaseImpl(port);
  @Test void invalidActorAndScopeNeverRead() {
    assertThatThrownBy(()->useCase.get(null,rank,round)).isInstanceOf(RankingEntriesDeniedException.class);
    assertThatThrownBy(()->useCase.get(new RankingActor(user,false),rank,round)).isInstanceOf(RankingEntriesDeniedException.class);
    assertThatThrownBy(()->useCase.get(actor,null,round)).isInstanceOf(RankingEntriesDeniedException.class);
    assertThatThrownBy(()->useCase.get(actor,rank,null)).isInstanceOf(RankingEntriesDeniedException.class);
    verifyNoInteractions(port);
  }
  @Test void preservesEveryHistoricalValueAndScopesOnlyToAuthenticatedUser() {
    for(Integer score:Arrays.asList(null,Integer.MIN_VALUE,-1,0,10,11,Integer.MAX_VALUE)) {
      var view=new OwnRoundRatingView(round,score);
      when(port.find(rank,round,user)).thenReturn(Optional.of(view));
      assertThat(useCase.get(actor,rank,round)).isSameAs(view);
    }
    verify(port,times(7)).find(rank,round,user);verifyNoMoreInteractions(port);
  }
  @Test void missingMalformedAndCrossRoundPortResultsFailClosed() {
    when(port.find(rank,round,user)).thenReturn(null);
    assertThatThrownBy(()->useCase.get(actor,rank,round)).isInstanceOf(RankingEntriesDeniedException.class);
    when(port.find(rank,round,user)).thenReturn(Optional.empty());
    assertThatThrownBy(()->useCase.get(actor,rank,round)).isInstanceOf(RankingEntriesDeniedException.class);
    for(UUID id:Arrays.asList(null,UUID.randomUUID())) {
      when(port.find(rank,round,user)).thenReturn(Optional.of(new OwnRoundRatingView(id,8)));
      assertThatThrownBy(()->useCase.get(actor,rank,round)).isInstanceOf(RankingEntriesDeniedException.class);
    }
  }
  @Test void adapterDenialIsPropagatedOnceWithoutFallbackOrRetry() {
    var denied=new RankingEntriesDeniedException();
    when(port.find(rank,round,user)).thenThrow(denied);
    assertThatThrownBy(()->useCase.get(actor,rank,round)).isSameAs(denied);
    verify(port).find(rank,round,user);verifyNoMoreInteractions(port);
    assertThatNullPointerException().isThrownBy(()->new GetOwnRoundRatingUseCaseImpl(null));
  }
}
