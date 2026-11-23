package com.moviemux.ranking.domain.usecase.impl;

import com.moviemux.ranking.domain.exception.InvalidRankingInvitationException;
import com.moviemux.ranking.domain.exception.RankingInvitationNotFoundException;
import com.moviemux.ranking.domain.model.RankingActor;
import com.moviemux.ranking.domain.model.RankingInvitationAddress;
import com.moviemux.ranking.domain.model.RankingInvitationReceipt;
import com.moviemux.ranking.domain.port.RankingInvitationCommands;
import com.moviemux.ranking.domain.usecase.ManageRankingInvitationsUseCase;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(isolation = Isolation.READ_COMMITTED)
public class ManageRankingInvitationsUseCaseImpl implements ManageRankingInvitationsUseCase {
  private final RankingInvitationCommands commands;

  public ManageRankingInvitationsUseCaseImpl(RankingInvitationCommands commands) {
    this.commands = Objects.requireNonNull(commands, "commands");
  }

  @Override
  public RankingInvitationReceipt issue(RankingActor actor, UUID rankingId, String recipientCode) {
    UUID actorId = actorId(actor, rankingId);
    if (!RankingInvitationAddress.isCanonical(recipientCode)) throw new InvalidRankingInvitationException();
    var receipt = commands.issue(actorId, rankingId, recipientCode);
    if (receipt == null || receipt.inviteId() == null || receipt.expiresAt() == null) {
      throw new RankingInvitationNotFoundException();
    }
    return receipt;
  }

  @Override
  public void accept(RankingActor actor, UUID rankingId, UUID inviteId) {
    UUID actorId = actorId(actor, rankingId);
    requireInvitationId(inviteId);
    commands.accept(actorId, rankingId, inviteId);
  }

  @Override
  public void revoke(RankingActor actor, UUID rankingId, UUID inviteId) {
    UUID actorId = actorId(actor, rankingId);
    requireInvitationId(inviteId);
    commands.revoke(actorId, rankingId, inviteId);
  }

  private UUID actorId(RankingActor actor, UUID rankingId) {
    if (actor == null || !actor.accountEnabled() || rankingId == null) {
      throw new RankingInvitationNotFoundException();
    }
    return actor.userId();
  }

  private void requireInvitationId(UUID inviteId) {
    if (inviteId == null) throw new RankingInvitationNotFoundException();
  }
}
