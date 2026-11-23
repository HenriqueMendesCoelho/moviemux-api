package com.moviemux.ranking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import com.moviemux.ranking.domain.exception.InvalidRankingAccessPageException;
import com.moviemux.ranking.domain.exception.InvalidRankingCapabilitiesException;
import com.moviemux.ranking.domain.exception.RankingAccessDeniedException;
import com.moviemux.ranking.domain.model.RankingActor;
import com.moviemux.ranking.domain.model.RankingCapability;
import com.moviemux.ranking.domain.model.RosterMember;
import com.moviemux.ranking.domain.port.RankingMembershipRoster;
import com.moviemux.ranking.domain.usecase.impl.ManageMembershipRosterUseCaseImpl;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ManageMembershipRosterUseCaseTest {
  private static final UUID RANK = new UUID(0, 1), OWNER = new UUID(0, 2);
  private static final UUID FIRST = UUID.fromString("7fffffff-ffff-ffff-ffff-ffffffffffff");
  private static final UUID SECOND = UUID.fromString("80000000-0000-0000-0000-000000000000");
  private static final RankingActor ACTOR = new RankingActor(OWNER, true);
  private final RankingMembershipRoster store = mock(RankingMembershipRoster.class);
  private final ManageMembershipRosterUseCaseImpl useCase = new ManageMembershipRosterUseCaseImpl(store);

  @Test void pageUsesDatabaseOrderAndOneExtraRowWithoutPublishingIt() {
    var first = new RosterMember(FIRST, "Display", Set.of(RankingCapability.READ));
    var second = new RosterMember(SECOND, null, Set.of());
    when(store.find(RANK, OWNER, null, 2)).thenReturn(List.of(first,second));
    var page = useCase.list(ACTOR, RANK, null, 1);
    assertThat(page.items()).containsExactly(first);
    assertThat(page.nextCursor()).isEqualTo(FIRST);
    when(store.find(RANK, OWNER, FIRST, 2)).thenReturn(List.of(second));
    var tail = useCase.list(ACTOR, RANK, FIRST, 1);
    assertThat(tail.items()).containsExactly(second);
    assertThat(tail.nextCursor()).isNull();
    when(store.find(RANK, OWNER, SECOND, 2)).thenReturn(List.of());
    assertThat(useCase.list(ACTOR, RANK, SECOND, 1).items()).isEmpty();
  }

  @Test void pageFailsClosedForNullOversizedOrUnorderedRows() {
    var first = new RosterMember(FIRST, "Display", Set.of());
    var second = new RosterMember(SECOND, null, Set.of());
    for (var result : Arrays.<List<RosterMember>>asList(null, List.of(first,second,second),
        Collections.singletonList(null), List.of(second,first), List.of(first,first))) {
      reset(store);
      when(store.find(RANK, OWNER, null, 2)).thenReturn(result);
      assertThatThrownBy(()->useCase.list(ACTOR, RANK, null, 1)).isInstanceOf(RankingAccessDeniedException.class);
    }
    when(store.find(RANK, OWNER, FIRST, 2)).thenReturn(List.of(first));
    assertThatThrownBy(()->useCase.list(ACTOR, RANK, FIRST, 1)).isInstanceOf(RankingAccessDeniedException.class);
  }

  @Test void unusableActorScopePageOrGrantSetNeverReachesStore() {
    assertThatThrownBy(()->useCase.list(null,RANK,null,20)).isInstanceOf(RankingAccessDeniedException.class);
    assertThatThrownBy(()->useCase.list(new RankingActor(OWNER,false),RANK,null,20))
        .isInstanceOf(RankingAccessDeniedException.class);
    assertThatThrownBy(()->useCase.list(ACTOR,null,null,20)).isInstanceOf(RankingAccessDeniedException.class);
    for (int size : List.of(0,51))
      assertThatThrownBy(()->useCase.list(ACTOR,RANK,null,size)).isInstanceOf(InvalidRankingAccessPageException.class);
    assertThatThrownBy(()->useCase.replace(ACTOR,RANK,null,Set.of())).isInstanceOf(RankingAccessDeniedException.class);
    assertThatThrownBy(()->useCase.replace(ACTOR,RANK,FIRST,null)).isInstanceOf(InvalidRankingCapabilitiesException.class);
    var bad = new HashSet<RankingCapability>();
    bad.add(null);
    assertThatThrownBy(()->useCase.replace(ACTOR,RANK,FIRST,bad)).isInstanceOf(InvalidRankingCapabilitiesException.class);
    verifyNoInteractions(store);
  }

  @Test void replacementCanClearAllAndNeverTreatsUnknownTargetAsSuccess() {
    when(store.replace(RANK,OWNER,FIRST,Set.of())).thenReturn(true);
    useCase.replace(ACTOR,RANK,FIRST,Set.of());
    verify(store).replace(RANK,OWNER,FIRST,Set.of());
    assertThatThrownBy(()->useCase.replace(ACTOR,RANK,SECOND,Set.of(RankingCapability.READ)))
        .isInstanceOf(RankingAccessDeniedException.class);
  }
}
