package com.moviemux.rankingcontent;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.moviemux.ranking.domain.model.RankingActor;
import com.moviemux.rankingcontent.domain.exception.RankingEntriesDeniedException;
import com.moviemux.rankingcontent.domain.model.RoundRevealView;
import com.moviemux.rankingcontent.domain.port.ReadRoundAndRevealIfDue;
import com.moviemux.rankingcontent.domain.usecase.impl.ReadRankingRoundUseCaseImpl;
import java.util.Arrays;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ReadRankingRoundUseCaseTest {
  private static final UUID USER=new UUID(0,1), RANK=new UUID(0,2), ENTRY=new UUID(0,3), ROUND=new UUID(0,4);
  private static final RankingActor ACTOR=new RankingActor(USER,true);
  private final ReadRoundAndRevealIfDue rounds=mock(ReadRoundAndRevealIfDue.class);
  private final ReadRankingRoundUseCaseImpl useCase=new ReadRankingRoundUseCaseImpl(rounds);

  @Test void deniedIdentityOrMissingCompoundScopeNeverReachesPort() {
    for (var actor:Arrays.asList(null,new RankingActor(USER,false))) {
      assertThatThrownBy(()->useCase.read(actor,RANK,ENTRY,ROUND)).isInstanceOf(RankingEntriesDeniedException.class);
    }
    assertThatThrownBy(()->useCase.read(ACTOR,null,ENTRY,ROUND)).isInstanceOf(RankingEntriesDeniedException.class);
    assertThatThrownBy(()->useCase.read(ACTOR,RANK,null,ROUND)).isInstanceOf(RankingEntriesDeniedException.class);
    assertThatThrownBy(()->useCase.read(ACTOR,RANK,ENTRY,null)).isInstanceOf(RankingEntriesDeniedException.class);
    verifyNoInteractions(rounds);
  }

  @Test void forwardsExactActorAndCompoundScopeAndPreservesEveryRevealState() {
    for (Boolean revealed:Arrays.asList(null,Boolean.FALSE,Boolean.TRUE)) {
      var expected=new RoundRevealView(ROUND,Long.MAX_VALUE,revealed);
      when(rounds.read(RANK,ENTRY,ROUND,USER)).thenReturn(Optional.of(expected));
      assertThat(useCase.read(ACTOR,RANK,ENTRY,ROUND)).isSameAs(expected);
    }
    verify(rounds,times(3)).read(RANK,ENTRY,ROUND,USER);
    verifyNoMoreInteractions(rounds);
  }

  @Test void absentAndMalformedPortResultsFailClosedWithoutAlternateLookup() {
    for (Optional<RoundRevealView> result:Arrays.asList(null,Optional.<RoundRevealView>empty(),
        Optional.of(new RoundRevealView(null,1,true)),Optional.of(new RoundRevealView(ENTRY,1,true)),
        Optional.of(new RoundRevealView(ROUND,0,true)),Optional.of(new RoundRevealView(ROUND,-1,false)))) {
      when(rounds.read(RANK,ENTRY,ROUND,USER)).thenReturn(result);
      assertThatThrownBy(()->useCase.read(ACTOR,RANK,ENTRY,ROUND)).isInstanceOf(RankingEntriesDeniedException.class);
    }
    verify(rounds,times(6)).read(RANK,ENTRY,ROUND,USER);
    verifyNoMoreInteractions(rounds);
  }
}
