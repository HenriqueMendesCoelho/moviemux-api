package com.moviemux.ranking;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.moviemux.ranking.domain.exception.DuplicateRankingInvitationException;
import com.moviemux.ranking.domain.exception.InvalidRankingInvitationException;
import com.moviemux.ranking.domain.exception.RankingInvitationNotFoundException;
import com.moviemux.ranking.domain.model.RankingActor;
import com.moviemux.ranking.domain.model.RankingInvitationReceipt;
import com.moviemux.ranking.domain.port.RankingInvitationCommands;
import com.moviemux.ranking.domain.usecase.impl.ManageRankingInvitationsUseCaseImpl;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ManageRankingInvitationsUseCaseTest {
  private final UUID issuer = new UUID(0, 1);
  private final UUID recipient = new UUID(0, 2);
  private final UUID ranking = new UUID(0, 3);
  private final UUID invite = new UUID(0, 4);
  private final OffsetDateTime expiry = OffsetDateTime.parse("2026-11-30T12:00:00Z");
  private final RankingActor actor = new RankingActor(issuer, true);
  private final RankingInvitationCommands commands = mock(RankingInvitationCommands.class);
  private final ManageRankingInvitationsUseCaseImpl useCase = new ManageRankingInvitationsUseCaseImpl(commands);

  @Test
  void issueReturnsOnlyTheReceiptAndUsesExactlyTheActorAndRank() {
    var expected = new RankingInvitationReceipt(invite, expiry);
    when(commands.issue(issuer, ranking, recipient)).thenReturn(expected);
    assertThat(useCase.issue(actor, ranking, recipient)).isSameAs(expected);
    verify(commands).issue(issuer, ranking, recipient);
    verifyNoMoreInteractions(commands);
  }

  @Test
  void acceptAndRevokeUseTheAuthenticatedAccountAndSelectedRanking() {
    useCase.accept(new RankingActor(recipient, true), ranking, invite);
    useCase.revoke(actor, ranking, invite);
    var calls = inOrder(commands);
    calls.verify(commands).accept(recipient, ranking, invite);
    calls.verify(commands).revoke(issuer, ranking, invite);
    verifyNoMoreInteractions(commands);
  }

  @Test
  void absentOrDisabledActorsAndMissingRankNeverReachThePort() {
    assertDenied(null, ranking);
    assertDenied(new RankingActor(issuer, false), ranking);
    assertDenied(actor, null);
    verifyNoInteractions(commands);
  }

  @Test
  void missingRecipientIsInvalidAndMissingInviteIdIsNotFound() {
    assertThatThrownBy(() -> useCase.issue(actor, ranking, null))
        .isInstanceOf(InvalidRankingInvitationException.class);
    assertThatThrownBy(() -> useCase.accept(actor, ranking, null))
        .isInstanceOf(RankingInvitationNotFoundException.class);
    assertThatThrownBy(() -> useCase.revoke(actor, ranking, null))
        .isInstanceOf(RankingInvitationNotFoundException.class);
    verifyNoInteractions(commands);
  }

  @Test
  void incompleteAdapterReceiptFailsClosedWithoutARepeatWrite() {
    when(commands.issue(issuer, ranking, recipient)).thenReturn(
        null, new RankingInvitationReceipt(null, expiry), new RankingInvitationReceipt(invite, null));
    for (int i = 0; i < 3; i++) {
      assertThatThrownBy(() -> useCase.issue(actor, ranking, recipient))
          .isInstanceOf(RankingInvitationNotFoundException.class);
    }
    verify(commands, times(3)).issue(issuer, ranking, recipient);
    verifyNoMoreInteractions(commands);
  }

  @Test
  void duplicateIsPreservedAndDenialsAreNeverRetriedOrReinterpreted() {
    var duplicate = new DuplicateRankingInvitationException();
    var denied = new RankingInvitationNotFoundException();
    when(commands.issue(issuer, ranking, recipient)).thenThrow(duplicate);
    doThrow(denied).when(commands).accept(recipient, ranking, invite);
    doThrow(denied).when(commands).revoke(issuer, ranking, invite);
    assertThatThrownBy(() -> useCase.issue(actor, ranking, recipient)).isSameAs(duplicate);
    assertThatThrownBy(() -> useCase.accept(new RankingActor(recipient, true), ranking, invite)).isSameAs(denied);
    assertThatThrownBy(() -> useCase.revoke(actor, ranking, invite)).isSameAs(denied);
    verify(commands).issue(issuer, ranking, recipient);
    verify(commands).accept(recipient, ranking, invite);
    verify(commands).revoke(issuer, ranking, invite);
    verifyNoMoreInteractions(commands);
  }

  private void assertDenied(RankingActor input, UUID rankingId) {
    assertThatThrownBy(() -> useCase.issue(input, rankingId, recipient))
        .isInstanceOf(RankingInvitationNotFoundException.class);
    assertThatThrownBy(() -> useCase.accept(input, rankingId, invite))
        .isInstanceOf(RankingInvitationNotFoundException.class);
    assertThatThrownBy(() -> useCase.revoke(input, rankingId, invite))
        .isInstanceOf(RankingInvitationNotFoundException.class);
  }
}
