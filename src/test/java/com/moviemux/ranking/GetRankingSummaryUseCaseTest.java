package com.moviemux.ranking;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.moviemux.ranking.domain.exception.RankingAccessDeniedException;
import com.moviemux.ranking.domain.model.*;
import com.moviemux.ranking.domain.port.CurrentRankingSummary;
import com.moviemux.ranking.domain.usecase.impl.GetRankingSummaryUseCaseImpl;
import java.util.Arrays;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class GetRankingSummaryUseCaseTest {
  private static final UUID ACCOUNT = new UUID(0, 1);
  private static final UUID RANK = new UUID(0, 2);
  private static final RankingActor ACTOR = new RankingActor(ACCOUNT, true);
  private final CurrentRankingSummary summaries = mock(CurrentRankingSummary.class);
  private final GetRankingSummaryUseCaseImpl useCase = new GetRankingSummaryUseCaseImpl(summaries);

  @Test void invalidActorsAndMissingScopeNeverRead() {
    assertThatThrownBy(() -> useCase.get(null, RANK)).isInstanceOf(RankingAccessDeniedException.class);
    assertThatThrownBy(() -> useCase.get(new RankingActor(ACCOUNT, false), RANK)).isInstanceOf(RankingAccessDeniedException.class);
    assertThatThrownBy(() -> useCase.get(ACTOR, null)).isInstanceOf(RankingAccessDeniedException.class);
    verifyNoInteractions(summaries);
  }

  @Test void allowedMetadataIsScopedToActorAndRankAndPreservesLegacyNull() {
    var allowed = new RankingContextSummary(summary(RANK, Set.of(RankingCapability.READ)), false);
    when(summaries.find(ACCOUNT, RANK)).thenReturn(Optional.of(allowed));
    assertThat(useCase.get(ACTOR, RANK)).isSameAs(allowed);
    assertThat(allowed.summary().name()).isNull();
    verify(summaries).find(ACCOUNT, RANK);
    verifyNoMoreInteractions(summaries);
  }

  @Test void missingWrongIdAndMalformedProjectionFailClosed() {
    Set<RankingCapability> read = Set.of(RankingCapability.READ);
    for (Optional<RankingContextSummary> denied : Arrays.asList(null, Optional.<RankingContextSummary>empty(),
        Optional.of(new RankingContextSummary(null, false)),
        Optional.of(new RankingContextSummary(summary(new UUID(0, 3), read), false)),
        Optional.of(new RankingContextSummary(summary(null, read), false)),
        Optional.of(new RankingContextSummary(summary(RANK, null), false)),
        Optional.of(new RankingContextSummary(summary(RANK, Set.of(RankingCapability.ADD_ENTRY)), true)),
        Optional.of(new RankingContextSummary(new RankingSummary(RANK, null, null, RankingAdmission.OPEN, read), true)),
        Optional.of(new RankingContextSummary(new RankingSummary(RANK, null, RankingVisibility.PRIVATE, null, read), true)))) {
      when(summaries.find(ACCOUNT, RANK)).thenReturn(denied);
      assertThatThrownBy(() -> useCase.get(ACTOR, RANK)).isInstanceOf(RankingAccessDeniedException.class);
    }
    verify(summaries, times(9)).find(ACCOUNT, RANK);
    verifyNoMoreInteractions(summaries);
  }

  @Test void sharingProofComesOnlyFromTheAuthorizedSnapshotAndIsMaskedForNonOpen() {
    for (var admission : RankingAdmission.values()) {
      var summary = new RankingSummary(RANK, null, RankingVisibility.PRIVATE, admission,
          java.util.EnumSet.allOf(RankingCapability.class));
      when(summaries.find(ACCOUNT, RANK)).thenReturn(Optional.of(new RankingContextSummary(summary, false)),
          Optional.of(new RankingContextSummary(summary, true)));
      assertThat(useCase.get(ACTOR, RANK).canShareOpenLink()).isFalse();
      assertThat(useCase.get(ACTOR, RANK).canShareOpenLink()).isEqualTo(admission == RankingAdmission.OPEN);
    }
  }

  private static RankingSummary summary(UUID rank, Set<RankingCapability> capabilities) {
    return new RankingSummary(rank, null, RankingVisibility.PRIVATE, RankingAdmission.INVITATION, capabilities);
  }
}
