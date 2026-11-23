package com.moviemux.ranking;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.moviemux.ranking.domain.exception.RankingAccessDeniedException;
import com.moviemux.ranking.domain.model.RankingActor;
import com.moviemux.ranking.domain.port.JoinOpenRanking;
import com.moviemux.ranking.domain.usecase.impl.JoinOpenRankingUseCaseImpl;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class JoinOpenRankingUseCaseTest {
  private static final UUID RANK = new UUID(0, 1), USER = new UUID(0, 2);
  private static final RankingActor ACTOR = new RankingActor(USER, true);
  private final JoinOpenRanking rankings = mock(JoinOpenRanking.class);
  private final JoinOpenRankingUseCaseImpl useCase = new JoinOpenRankingUseCaseImpl(rankings);

  @Test void invalidActorAndScopeNeverReachStorage() {
    assertThatThrownBy(() -> useCase.join(null, RANK)).isInstanceOf(RankingAccessDeniedException.class);
    assertThatThrownBy(() -> useCase.join(new RankingActor(USER, false), RANK)).isInstanceOf(RankingAccessDeniedException.class);
    assertThatThrownBy(() -> useCase.join(ACTOR, null)).isInstanceOf(RankingAccessDeniedException.class);
    verifyNoInteractions(rankings);
  }

  @Test void trueCompletesAndFalseDeniesWithoutRetry() {
    when(rankings.join(RANK, USER)).thenReturn(true, false);
    assertThatCode(() -> useCase.join(ACTOR, RANK)).doesNotThrowAnyException();
    assertThatThrownBy(() -> useCase.join(ACTOR, RANK)).isInstanceOf(RankingAccessDeniedException.class);
    verify(rankings, times(2)).join(RANK, USER);
    verifyNoMoreInteractions(rankings);
  }
}
