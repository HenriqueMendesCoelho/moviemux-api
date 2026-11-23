package com.moviemux.ranking;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.moviemux.ranking.domain.exception.InvalidRankingAccessPageException;
import com.moviemux.ranking.domain.exception.RankingInvitationNotFoundException;
import com.moviemux.ranking.domain.model.IncomingRankingInvitation;
import com.moviemux.ranking.domain.model.RankingActor;
import com.moviemux.ranking.domain.port.ListCurrentIncomingRankingInvitations;
import com.moviemux.ranking.domain.usecase.impl.ListIncomingRankingInvitationsUseCaseImpl;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ListIncomingRankingInvitationsUseCaseTest {
  private static final UUID ACTOR = new UUID(0, 1), RANK = new UUID(0, 2);
  private static final UUID FIRST = UUID.fromString("7fffffff-ffff-ffff-ffff-ffffffffffff");
  private static final UUID NEXT = UUID.fromString("80000000-0000-0000-0000-000000000000");
  private static final OffsetDateTime EXPIRY = OffsetDateTime.parse("2026-11-30T12:00:00Z");
  private final RankingActor actor = new RankingActor(ACTOR, true);
  private final ListCurrentIncomingRankingInvitations inbox = mock(ListCurrentIncomingRankingInvitations.class);
  private final ListIncomingRankingInvitationsUseCaseImpl useCase = new ListIncomingRankingInvitationsUseCaseImpl(inbox);

  @Test void invalidActorAndPageBoundsNeverReachStorage() {
    assertThatThrownBy(() -> useCase.list(null, null, 20)).isInstanceOf(RankingInvitationNotFoundException.class);
    assertThatThrownBy(() -> useCase.list(new RankingActor(ACTOR, false), null, 20))
        .isInstanceOf(RankingInvitationNotFoundException.class);
    for (int limit : new int[]{0,-1,101,Integer.MAX_VALUE}) {
      assertThatThrownBy(() -> useCase.list(actor, null, limit)).isInstanceOf(InvalidRankingAccessPageException.class);
    }
    verifyNoInteractions(inbox);
  }

  @Test void lookaheadUsesUnsignedUuidOrderAndExposesOnlyLastPublishedCursor() {
    when(inbox.find(ACTOR, null, 2)).thenReturn(List.of(invite(FIRST), invite(NEXT)));
    when(inbox.find(ACTOR, FIRST, 2)).thenReturn(List.of(invite(NEXT)));
    when(inbox.find(ACTOR, NEXT, 101)).thenReturn(List.of());
    var first = useCase.list(actor, null, 1);
    assertThat(first.items()).containsExactly(invite(FIRST));
    assertThat(first.nextCursor()).isEqualTo(FIRST);
    var last = useCase.list(actor, FIRST, 1);
    assertThat(last.items()).containsExactly(invite(NEXT));
    assertThat(last.nextCursor()).isNull();
    var empty = useCase.list(actor, NEXT, 100);
    assertThat(empty.items()).isEmpty();
    assertThat(empty.nextCursor()).isNull();
    assertThatThrownBy(() -> first.items().clear()).isInstanceOf(UnsupportedOperationException.class);
    verify(inbox).find(ACTOR, null, 2);
    verify(inbox).find(ACTOR, FIRST, 2);
    verify(inbox).find(ACTOR, NEXT, 101);
    verifyNoMoreInteractions(inbox);
  }

  @Test void invalidLookaheadOrExclusiveCursorMakesEntirePageUnavailable() {
    List<List<IncomingRankingInvitation>> invalid = Arrays.asList(null,
        List.of(invite(FIRST), invite(NEXT), invite(new UUID(-1,-1))),
        Arrays.asList(invite(FIRST), null), List.of(invite(FIRST), invite(null)),
        List.of(invite(FIRST), new IncomingRankingInvitation(NEXT,null,null,EXPIRY)),
        List.of(invite(FIRST), new IncomingRankingInvitation(NEXT,RANK,null,null)),
        List.of(invite(FIRST), invite(FIRST)), List.of(invite(NEXT), invite(FIRST)));
    for (var rows : invalid) {
      when(inbox.find(ACTOR, null, 2)).thenReturn(rows);
      assertThatThrownBy(() -> useCase.list(actor, null, 1)).isInstanceOf(RankingInvitationNotFoundException.class);
    }
    when(inbox.find(ACTOR, FIRST, 2)).thenReturn(List.of(invite(FIRST)));
    assertThatThrownBy(() -> useCase.list(actor, FIRST, 1)).isInstanceOf(RankingInvitationNotFoundException.class);
  }

  private static IncomingRankingInvitation invite(UUID id) { return new IncomingRankingInvitation(id, RANK, null, EXPIRY); }
}
