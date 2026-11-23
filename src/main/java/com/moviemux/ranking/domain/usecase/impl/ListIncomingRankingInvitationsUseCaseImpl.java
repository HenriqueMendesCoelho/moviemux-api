package com.moviemux.ranking.domain.usecase.impl;

import com.moviemux.ranking.domain.exception.InvalidRankingAccessPageException;
import com.moviemux.ranking.domain.exception.RankingInvitationNotFoundException;
import com.moviemux.ranking.domain.model.IncomingRankingInvitationPage;
import com.moviemux.ranking.domain.model.RankingActor;
import com.moviemux.ranking.domain.port.ListCurrentIncomingRankingInvitations;
import com.moviemux.ranking.domain.usecase.ListIncomingRankingInvitationsUseCase;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true, isolation = Isolation.READ_COMMITTED)
public class ListIncomingRankingInvitationsUseCaseImpl implements ListIncomingRankingInvitationsUseCase {
  private final ListCurrentIncomingRankingInvitations inbox;

  public ListIncomingRankingInvitationsUseCaseImpl(ListCurrentIncomingRankingInvitations inbox) {
    this.inbox = Objects.requireNonNull(inbox, "inbox");
  }

  @Override
  public IncomingRankingInvitationPage list(RankingActor actor, UUID after, int limit) {
    if (actor == null || !actor.accountEnabled()) throw new RankingInvitationNotFoundException();
    if (limit < 1 || limit > 100) throw new InvalidRankingAccessPageException();
    var found = inbox.find(actor.userId(), after, limit + 1);
    if (found == null || found.size() > limit + 1) throw new RankingInvitationNotFoundException();
    UUID previous = after;
    for (var item : found) {
      if (item == null || item.inviteId() == null || item.rankingId() == null || item.expiresAt() == null
          || (previous != null && previous.toString().compareTo(item.inviteId().toString()) >= 0))
        throw new RankingInvitationNotFoundException();
      previous = item.inviteId(); // PostgreSQL orders UUID by unsigned bytes.
    }
    var page = found.subList(0, Math.min(limit, found.size()));
    return new IncomingRankingInvitationPage(page, found.size() > limit ? page.get(page.size() - 1).inviteId() : null);
  }
}
