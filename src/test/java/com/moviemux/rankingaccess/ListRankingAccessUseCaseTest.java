package com.moviemux.rankingaccess;

import static org.assertj.core.api.Assertions.*;
import com.moviemux.ranking.domain.exception.*;
import com.moviemux.ranking.domain.model.*;
import com.moviemux.ranking.domain.usecase.impl.ListRankingAccessUseCaseImpl;
import java.util.*;
import org.junit.jupiter.api.Test;

class ListRankingAccessUseCaseTest {
  private final UUID actorId = UUID.randomUUID();
  private final RankingActor actor = new RankingActor(actorId, true);
  private final UUID low = UUID.fromString("7fffffff-ffff-ffff-ffff-ffffffffffff");
  private final UUID high = UUID.fromString("80000000-0000-0000-0000-000000000000");

  @Test void preservesPostgresUnsignedUuidOrderAndUsesLastReturnedCursor() {
    var useCase = new ListRankingAccessUseCaseImpl((id, after, bound) -> {
      assertThat(id).isEqualTo(actorId); assertThat(bound).isEqualTo(2);
      return List.of(snapshot(low, actorId, true, Set.of(RankingCapability.READ)), snapshot(high, actorId, true, Set.of(RankingCapability.READ)));
    });
    var page = useCase.execute(actor, null, 1);
    assertThat(page.items()).extracting(RankingAccessView::rankingId).containsExactly(low);
    assertThat(page.nextCursor()).isEqualTo(low);
    assertThat(new ListRankingAccessUseCaseImpl((id, after, bound) -> List.of(snapshot(high, actorId, true, Set.of(RankingCapability.READ))))
        .execute(actor, low, 20).nextCursor()).isNull();
  }

  @Test void invalidActorAndLimitsNeverQuery() {
    var useCase = new ListRankingAccessUseCaseImpl((id, after, bound) -> { throw new AssertionError("Must not query"); });
    assertThatThrownBy(() -> useCase.execute(null, null, 20)).isInstanceOf(RankingAccessDeniedException.class);
    assertThatThrownBy(() -> useCase.execute(new RankingActor(actorId, false), null, 20)).isInstanceOf(RankingAccessDeniedException.class);
    for (int limit : new int[]{-1, 0, 101, Integer.MAX_VALUE})
      assertThatThrownBy(() -> useCase.execute(actor, null, limit)).isInstanceOf(InvalidRankingAccessPageException.class);
  }

  @Test void malformedPortDataFailsClosedIncludingLookahead() {
    var valid = snapshot(low, actorId, true, Set.of(RankingCapability.READ));
    var invalid = List.of(
        snapshot(high, UUID.randomUUID(), true, Set.of(RankingCapability.READ)),
        snapshot(high, actorId, false, Set.of(RankingCapability.READ)),
        snapshot(high, actorId, true, Set.of(RankingCapability.RATE_OWN)),
        new RankingAccessSnapshot(new RankingIdentity(high, UUID.randomUUID(), RankingVisibility.PRIVATE, RankingAdmission.INVITATION), Optional.empty()));
    for (var bad : invalid) {
      var useCase = new ListRankingAccessUseCaseImpl((id, after, bound) -> List.of(valid, bad));
      assertThatThrownBy(() -> useCase.execute(actor, null, 1)).isInstanceOf(RankingAccessDeniedException.class);
    }
    for (List<RankingAccessSnapshot> rows : Arrays.asList(null, Arrays.asList(valid, null), List.of(valid, valid), List.of(valid, valid, valid))) {
      var useCase = new ListRankingAccessUseCaseImpl((id, after, bound) -> rows);
      assertThatThrownBy(() -> useCase.execute(actor, null, 1)).isInstanceOf(RankingAccessDeniedException.class);
    }
    assertThatThrownBy(() -> new ListRankingAccessUseCaseImpl((id, after, bound) -> List.of(valid))
        .execute(actor, low, 1)).isInstanceOf(RankingAccessDeniedException.class);
  }

  private RankingAccessSnapshot snapshot(UUID rank, UUID member, boolean active, Set<RankingCapability> grants) {
    return new RankingAccessSnapshot(new RankingIdentity(rank, UUID.randomUUID(), RankingVisibility.PRIVATE, RankingAdmission.INVITATION),
        Optional.of(new RankingMembership(rank, member, active, grants)));
  }
}
