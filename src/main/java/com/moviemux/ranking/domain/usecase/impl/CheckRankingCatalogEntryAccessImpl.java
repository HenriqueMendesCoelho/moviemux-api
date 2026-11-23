package com.moviemux.ranking.domain.usecase.impl;

import com.moviemux.ranking.domain.exception.RankingAccessDeniedException;
import com.moviemux.ranking.domain.model.RankingActor;
import com.moviemux.ranking.domain.model.RankingCapability;
import com.moviemux.ranking.domain.port.CurrentRankingAccess;
import com.moviemux.ranking.domain.repository.CheckRankingCatalogEntryAccess;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class CheckRankingCatalogEntryAccessImpl implements CheckRankingCatalogEntryAccess {
  private final CurrentRankingAccess access;

  public CheckRankingCatalogEntryAccessImpl(CurrentRankingAccess access) {
    this.access = Objects.requireNonNull(access, "access");
  }

  @Override
  public boolean check(RankingActor actor, UUID rankingId) {
    if (actor == null || !actor.accountEnabled() || rankingId == null) throw new RankingAccessDeniedException();
    var found = access.find(rankingId, actor.userId());
    if (found == null) throw new RankingAccessDeniedException();
    var snapshot = found.orElseThrow(RankingAccessDeniedException::new);
    var member = snapshot.membership().orElseThrow(RankingAccessDeniedException::new);
    if (!rankingId.equals(snapshot.ranking().id()) || !member.active() || !actor.userId().equals(member.userId())
        || (!actor.userId().equals(snapshot.ranking().ownerUserId())
            && !member.capabilities().contains(RankingCapability.ADD_ENTRY))) throw new RankingAccessDeniedException();
    return snapshot.ranking().archived();
  }
}
