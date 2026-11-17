package com.moviemux.rankingaccess;

import static org.assertj.core.api.Assertions.*;
import com.moviemux.ranking.domain.exception.*;
import com.moviemux.ranking.domain.model.RankingActor;
import com.moviemux.ranking.domain.port.LeaveOwnRankingMembership;
import com.moviemux.ranking.domain.usecase.impl.LeaveRankingUseCaseImpl;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class LeaveRankingUseCaseTest {
  private final UUID user = UUID.randomUUID(), rank = UUID.randomUUID();
  @Test void validatesActorBeforeAnyMutation() {
    var useCase = new LeaveRankingUseCaseImpl((r,a) -> { throw new AssertionError("Must not mutate"); });
    assertThatThrownBy(() -> useCase.execute(null,rank)).isInstanceOf(RankingAccessDeniedException.class);
    assertThatThrownBy(() -> useCase.execute(new RankingActor(user,false),rank)).isInstanceOf(RankingAccessDeniedException.class);
    assertThatThrownBy(() -> useCase.execute(new RankingActor(user,true),null)).isInstanceOf(RankingAccessDeniedException.class);
  }
  @Test void passesOnlyAuthenticatedIdentityAndMapsOutcomeWithoutReadPermissionCheck() {
    var useCase = new LeaveRankingUseCaseImpl((r,a) -> {
      assertThat(r).isEqualTo(rank); assertThat(a).isEqualTo(user);
      return LeaveOwnRankingMembership.Outcome.LEFT;
    });
    assertThatCode(() -> useCase.execute(new RankingActor(user,true),rank)).doesNotThrowAnyException();
  }
  @Test void missingAndInvalidPortOutcomeFailClosedAndOwnerRequiresSafeDestination() {
    var actor = new RankingActor(user,true);
    assertThatThrownBy(() -> new LeaveRankingUseCaseImpl((r,a) -> null).execute(actor,rank)).isInstanceOf(RankingAccessDeniedException.class);
    assertThatThrownBy(() -> new LeaveRankingUseCaseImpl((r,a) -> LeaveOwnRankingMembership.Outcome.NOT_FOUND).execute(actor,rank)).isInstanceOf(RankingAccessDeniedException.class);
    assertThatThrownBy(() -> new LeaveRankingUseCaseImpl((r,a) -> LeaveOwnRankingMembership.Outcome.OWNER_REQUIRES_DESTINATION).execute(actor,rank)).isInstanceOf(RankingOwnerExitRequiresDestinationException.class);
  }
}
