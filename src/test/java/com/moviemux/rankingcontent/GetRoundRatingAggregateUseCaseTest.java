package com.moviemux.rankingcontent;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.moviemux.ranking.domain.model.RankingActor;
import com.moviemux.rankingcontent.domain.exception.RankingEntriesDeniedException;
import com.moviemux.rankingcontent.domain.model.RoundRatingAggregateView;
import com.moviemux.rankingcontent.domain.port.ReadRoundRatingAggregate;
import com.moviemux.rankingcontent.domain.usecase.impl.GetRoundRatingAggregateUseCaseImpl;
import java.math.BigInteger;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class GetRoundRatingAggregateUseCaseTest {
  private static final UUID USER=new UUID(0,1), RANK=new UUID(0,2), ENTRY=new UUID(0,3), ROUND=new UUID(0,4);
  private static final RankingActor ACTOR=new RankingActor(USER,true);
  private final ReadRoundRatingAggregate rounds=mock(ReadRoundRatingAggregate.class);
  private final GetRoundRatingAggregateUseCaseImpl useCase=new GetRoundRatingAggregateUseCaseImpl(rounds);

  @Test void deniedIdentityOrMissingCompoundScopeNeverReachesPort() {
    for (var actor:Arrays.asList(null,new RankingActor(USER,false))) {
      assertThatThrownBy(()->useCase.get(actor,RANK,ENTRY,ROUND)).isInstanceOf(RankingEntriesDeniedException.class);
    }
    assertThatThrownBy(()->useCase.get(ACTOR,null,ENTRY,ROUND)).isInstanceOf(RankingEntriesDeniedException.class);
    assertThatThrownBy(()->useCase.get(ACTOR,RANK,null,ROUND)).isInstanceOf(RankingEntriesDeniedException.class);
    assertThatThrownBy(()->useCase.get(ACTOR,RANK,ENTRY,null)).isInstanceOf(RankingEntriesDeniedException.class);
    verifyNoInteractions(rounds);
  }

  @Test void forwardsExactActorAndCompoundScopeAndPreservesEligibleStates() {
    var zero=BigInteger.ZERO;
    var one=BigInteger.ONE;
    var huge=one.shiftLeft(128);
    for (var expected:List.of(
        new RoundRatingAggregateView(ROUND,null,null,null),
        new RoundRatingAggregateView(ROUND,false,null,null),
        new RoundRatingAggregateView(ROUND,true,zero,zero),
        new RoundRatingAggregateView(ROUND,true,zero,one),
        new RoundRatingAggregateView(ROUND,true,huge.negate(),huge),
        new RoundRatingAggregateView(ROUND,true,huge,huge))) {
      when(rounds.find(RANK,ENTRY,ROUND,USER)).thenReturn(Optional.of(expected));
      assertThat(useCase.get(ACTOR,RANK,ENTRY,ROUND)).isSameAs(expected);
    }
    verify(rounds,times(6)).find(RANK,ENTRY,ROUND,USER);
    verifyNoMoreInteractions(rounds);
  }

  @Test void absentAndMalformedPortResultsFailClosedWithoutAlternateLookup() {
    var zero=BigInteger.ZERO;
    var one=BigInteger.ONE;
    for (Optional<RoundRatingAggregateView> result:Arrays.asList(null,Optional.<RoundRatingAggregateView>empty(),
        Optional.of(new RoundRatingAggregateView(null,true,zero,zero)),
        Optional.of(new RoundRatingAggregateView(ENTRY,true,zero,zero)),
        Optional.of(new RoundRatingAggregateView(ROUND,true,null,zero)),
        Optional.of(new RoundRatingAggregateView(ROUND,true,zero,null)),
        Optional.of(new RoundRatingAggregateView(ROUND,true,zero,one.negate())),
        Optional.of(new RoundRatingAggregateView(ROUND,true,one,zero)),
        Optional.of(new RoundRatingAggregateView(ROUND,true,one.negate(),zero)),
        Optional.of(new RoundRatingAggregateView(ROUND,false,zero,null)),
        Optional.of(new RoundRatingAggregateView(ROUND,false,null,zero)),
        Optional.of(new RoundRatingAggregateView(ROUND,null,zero,null)),
        Optional.of(new RoundRatingAggregateView(ROUND,null,null,one)))) {
      when(rounds.find(RANK,ENTRY,ROUND,USER)).thenReturn(result);
      assertThatThrownBy(()->useCase.get(ACTOR,RANK,ENTRY,ROUND)).isInstanceOf(RankingEntriesDeniedException.class);
    }
    verify(rounds,times(13)).find(RANK,ENTRY,ROUND,USER);
    verifyNoMoreInteractions(rounds);
  }
}
