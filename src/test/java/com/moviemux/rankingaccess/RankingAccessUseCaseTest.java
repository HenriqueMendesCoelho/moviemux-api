package com.moviemux.rankingaccess;

import static org.assertj.core.api.Assertions.*;
import com.moviemux.ranking.domain.exception.RankingAccessDeniedException;
import com.moviemux.ranking.domain.model.*;
import com.moviemux.ranking.domain.service.RankingAuthorization;
import com.moviemux.ranking.domain.usecase.impl.GetRankingAccessUseCaseImpl;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class RankingAccessUseCaseTest {
  private final UUID rankingId = UUID.randomUUID();
  private final UUID ownerId = UUID.randomUUID();
  private final UUID memberId = UUID.randomUUID();
  private final RankingIdentity ranking = new RankingIdentity(rankingId, ownerId, RankingVisibility.PRIVATE, RankingAdmission.INVITATION);

  @Test void ownerHasEveryLocalCapabilityButOnlyAfterActiveMembershipAuthorization() {
    var membership = new RankingMembership(rankingId, ownerId, true, Set.of());
    var useCase = useCase(membership);
    assertThat(useCase.execute(new RankingActor(ownerId, true), rankingId).capabilities())
        .containsExactlyInAnyOrderElementsOf(EnumSet.allOf(RankingCapability.class));
    assertThatThrownBy(() -> useCase(new RankingMembership(rankingId, ownerId, false, Set.of()))
        .execute(new RankingActor(ownerId, true), rankingId)).isInstanceOf(RankingAccessDeniedException.class);
  }

  @Test void memberGetsOnlyCurrentGrantsAndSnapshotIsReadOnce() {
    var reads = new AtomicInteger();
    var membership = RankingMembership.newlyAdmitted(rankingId, memberId);
    var useCase = new GetRankingAccessUseCaseImpl(new RankingAuthorization((id, actor) -> {
      reads.incrementAndGet();
      return Optional.of(new RankingAccessSnapshot(ranking, Optional.of(membership)));
    }));
    var response = useCase.execute(new RankingActor(memberId, true), rankingId);
    assertThat(response.rankingId()).isEqualTo(rankingId);
    assertThat(response.capabilities()).containsExactlyInAnyOrder(RankingCapability.READ, RankingCapability.RATE_OWN);
    assertThat(reads.get()).isEqualTo(1);
  }

  @Test void revokedReadAndDisabledAccountNeverReceiveAnAccessView() {
    var noRead = new RankingMembership(rankingId, memberId, true, Set.of(RankingCapability.RATE_OWN));
    assertThatThrownBy(() -> useCase(noRead).execute(new RankingActor(memberId, true), rankingId))
        .isInstanceOf(RankingAccessDeniedException.class);
    assertThatThrownBy(() -> useCase(RankingMembership.newlyAdmitted(rankingId, memberId))
        .execute(new RankingActor(memberId, false), rankingId)).isInstanceOf(RankingAccessDeniedException.class);
  }

  @Test void immutableViewAndRequiredDependencies() {
    var grants = new HashSet<>(Set.of(RankingCapability.READ));
    var view = new RankingAccessView(rankingId, grants);
    grants.clear();
    assertThat(view.capabilities()).containsExactly(RankingCapability.READ);
    assertThatExceptionOfType(UnsupportedOperationException.class).isThrownBy(() -> view.capabilities().clear());
    assertThatNullPointerException().isThrownBy(() -> new RankingAccessView(null, Set.of()));
    assertThatNullPointerException().isThrownBy(() -> new RankingAccessView(rankingId, null));
    assertThatNullPointerException().isThrownBy(() -> new GetRankingAccessUseCaseImpl(null));
  }

  private GetRankingAccessUseCaseImpl useCase(RankingMembership membership) {
    return new GetRankingAccessUseCaseImpl(new RankingAuthorization((id, actor) ->
        Optional.of(new RankingAccessSnapshot(ranking, Optional.of(membership)))));
  }
}
