package com.moviemux.ranking;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.moviemux.ranking.domain.exception.InvalidVisibilitySettingsException;
import com.moviemux.ranking.domain.exception.RankingAccessDeniedException;
import com.moviemux.ranking.domain.model.RankingActor;
import com.moviemux.ranking.domain.model.RankingVisibility;
import com.moviemux.ranking.domain.port.RankingVisibilitySettings;
import com.moviemux.ranking.domain.usecase.impl.ManageRankingVisibilityUseCaseImpl;
import java.util.Arrays;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ManageRankingVisibilityUseCaseTest {
  private static final UUID RANK = new UUID(0, 1), USER = new UUID(0, 2);
  private static final RankingActor ACTOR = new RankingActor(USER, true);
  private final RankingVisibilitySettings settings = mock(RankingVisibilitySettings.class);
  private final ManageRankingVisibilityUseCaseImpl useCase = new ManageRankingVisibilityUseCaseImpl(settings);

  @Test void invalidActorInvalidScopeAndMissingValueNeverReachPort() {
    for (var invalid : Arrays.asList(null, new RankingActor(USER, false))) {
      assertThatThrownBy(() -> useCase.current(invalid, RANK)).isInstanceOf(RankingAccessDeniedException.class);
      assertThatThrownBy(() -> useCase.set(invalid, RANK, RankingVisibility.PUBLIC)).isInstanceOf(RankingAccessDeniedException.class);
    }
    assertThatThrownBy(() -> useCase.current(ACTOR, null)).isInstanceOf(RankingAccessDeniedException.class);
    assertThatThrownBy(() -> useCase.set(ACTOR, null, RankingVisibility.PUBLIC)).isInstanceOf(RankingAccessDeniedException.class);
    assertThatThrownBy(() -> useCase.set(ACTOR, RANK, null)).isInstanceOf(InvalidVisibilitySettingsException.class);
    verifyNoInteractions(settings);
  }

  @Test void delegatesDesiredValueAndNeverTogglesOrReadsAsPartOfWrite() {
    when(settings.current(RANK, USER)).thenReturn(RankingVisibility.PUBLIC);
    assertThat(useCase.current(ACTOR, RANK)).isEqualTo(RankingVisibility.PUBLIC);
    for(var value : RankingVisibility.values()) {
      when(settings.set(RANK, USER, value)).thenReturn(value);
      assertThat(useCase.set(ACTOR, RANK, value)).isEqualTo(value);
      assertThat(useCase.set(ACTOR, RANK, value)).isEqualTo(value);
      verify(settings,times(2)).set(RANK, USER, value);
    }
    verify(settings).current(RANK, USER);
    verifyNoMoreInteractions(settings);
  }

  @Test void absentOrMismatchedOutputNeverConfirmsSuccess() {
    when(settings.set(RANK, USER, RankingVisibility.PRIVATE)).thenReturn(null, RankingVisibility.PUBLIC);
    assertThatThrownBy(() -> useCase.current(ACTOR, RANK)).isInstanceOf(RankingAccessDeniedException.class);
    for (int i=0;i<2;i++)
      assertThatThrownBy(() -> useCase.set(ACTOR, RANK, RankingVisibility.PRIVATE)).isInstanceOf(RankingAccessDeniedException.class);
    verify(settings).current(RANK, USER);
    verify(settings,times(2)).set(RANK, USER, RankingVisibility.PRIVATE);
    verifyNoMoreInteractions(settings);
  }
}
