package com.moviemux.rankingcontent;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.moviemux.ranking.domain.model.RankingActor;
import com.moviemux.rankingcontent.domain.exception.*;
import com.moviemux.rankingcontent.domain.model.OwnRoundRating;
import com.moviemux.rankingcontent.domain.port.WriteOwnRoundRating;
import com.moviemux.rankingcontent.domain.usecase.impl.WriteOwnRoundRatingUseCaseImpl;
import java.util.*;
import org.junit.jupiter.api.Test;

class WriteOwnRoundRatingUseCaseTest {
  private final UUID ranking=UUID.randomUUID(),round=UUID.randomUUID(),user=UUID.randomUUID();
  private final RankingActor actor=new RankingActor(user,true);
  @Test void actorScopeAndScoreValidationPrecedeAllWrites() {
    var port=mock(WriteOwnRoundRating.class);var useCase=new WriteOwnRoundRatingUseCaseImpl(port);
    for(boolean create:new boolean[]{true,false}) {
      assertThatThrownBy(()->write(useCase,create,null,ranking,round,5)).isInstanceOf(RankingEntriesDeniedException.class);
      assertThatThrownBy(()->write(useCase,create,new RankingActor(user,false),ranking,round,5)).isInstanceOf(RankingEntriesDeniedException.class);
      assertThatThrownBy(()->write(useCase,create,actor,null,round,5)).isInstanceOf(RankingEntriesDeniedException.class);
      assertThatThrownBy(()->write(useCase,create,actor,ranking,null,5)).isInstanceOf(RankingEntriesDeniedException.class);
      for(Integer score:Arrays.asList(null,-1,11,Integer.MAX_VALUE,Integer.MIN_VALUE))
        assertThatThrownBy(()->write(useCase,create,actor,ranking,round,score)).isInstanceOf(InvalidOwnRoundRatingException.class);
    }
    verifyNoInteractions(port);
  }
  @Test void zeroAndTenAreValidAndCreateNeverFallsBackToUpdate() {
    for(int score:new int[]{0,10}) {
      var port=mock(WriteOwnRoundRating.class);var useCase=new WriteOwnRoundRatingUseCaseImpl(port);var result=new OwnRoundRating(round,score);
      when(port.create(ranking,round,user,score)).thenReturn(result);
      assertThat(useCase.create(actor,ranking,round,score)).isSameAs(result);
      verify(port).create(ranking,round,user,score);verifyNoMoreInteractions(port);
      reset(port);when(port.update(ranking,round,user,score)).thenReturn(result);
      assertThat(useCase.update(actor,ranking,round,score)).isSameAs(result);
      verify(port).update(ranking,round,user,score);verifyNoMoreInteractions(port);
    }
  }
  @Test void nullWrongRoundAndWrongScorePortResultsFailClosed() {
    for(var result:Arrays.asList(null,new OwnRoundRating(UUID.randomUUID(),5),new OwnRoundRating(round,9))) {
      var port=mock(WriteOwnRoundRating.class);var useCase=new WriteOwnRoundRatingUseCaseImpl(port);
      when(port.create(ranking,round,user,5)).thenReturn(result);when(port.update(ranking,round,user,5)).thenReturn(result);
      assertThatThrownBy(()->useCase.create(actor,ranking,round,5)).isInstanceOf(RankingEntriesDeniedException.class);
      assertThatThrownBy(()->useCase.update(actor,ranking,round,5)).isInstanceOf(RankingEntriesDeniedException.class);
    }
  }
  @Test void duplicateCreateAndMissingUpdateAreNotRetriedAsDifferentOperations() {
    var port=mock(WriteOwnRoundRating.class);var useCase=new WriteOwnRoundRatingUseCaseImpl(port);
    when(port.create(ranking,round,user,5)).thenThrow(new DuplicateOwnRoundRatingException());
    assertThatThrownBy(()->useCase.create(actor,ranking,round,5)).isInstanceOf(DuplicateOwnRoundRatingException.class);
    verify(port).create(ranking,round,user,5);verifyNoMoreInteractions(port);
    reset(port);when(port.update(ranking,round,user,5)).thenThrow(new RankingEntriesDeniedException());
    assertThatThrownBy(()->useCase.update(actor,ranking,round,5)).isInstanceOf(RankingEntriesDeniedException.class);
    verify(port).update(ranking,round,user,5);verifyNoMoreInteractions(port);
  }
  private OwnRoundRating write(WriteOwnRoundRatingUseCaseImpl useCase,boolean create,RankingActor actor,UUID rank,UUID round,Integer score){
    return create?useCase.create(actor,rank,round,score):useCase.update(actor,rank,round,score);
  }
}
