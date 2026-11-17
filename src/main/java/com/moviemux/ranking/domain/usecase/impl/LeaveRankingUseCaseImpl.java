package com.moviemux.ranking.domain.usecase.impl;

import com.moviemux.ranking.domain.exception.*;
import com.moviemux.ranking.domain.model.RankingActor;
import com.moviemux.ranking.domain.port.LeaveOwnRankingMembership;
import com.moviemux.ranking.domain.usecase.LeaveRankingUseCase;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class LeaveRankingUseCaseImpl implements LeaveRankingUseCase {
  private final LeaveOwnRankingMembership membership;
  public LeaveRankingUseCaseImpl(LeaveOwnRankingMembership membership) {
    this.membership = Objects.requireNonNull(membership, "membership");
  }
  @Override public void execute(RankingActor actor, UUID rankingId) {
    if (actor == null || !actor.accountEnabled() || rankingId == null) throw new RankingAccessDeniedException();
    var outcome = membership.leave(rankingId, actor.userId());
    if (outcome == null || outcome == LeaveOwnRankingMembership.Outcome.NOT_FOUND) throw new RankingAccessDeniedException();
    if (outcome == LeaveOwnRankingMembership.Outcome.OWNER_REQUIRES_DESTINATION)
      throw new RankingOwnerExitRequiresDestinationException();
  }
}
