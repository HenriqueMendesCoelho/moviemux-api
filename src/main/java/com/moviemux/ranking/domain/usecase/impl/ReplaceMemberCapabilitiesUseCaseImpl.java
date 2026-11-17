package com.moviemux.ranking.domain.usecase.impl;

import com.moviemux.ranking.domain.exception.*;
import com.moviemux.ranking.domain.model.*;
import com.moviemux.ranking.domain.port.ReplaceRankingMemberCapabilities;
import com.moviemux.ranking.domain.usecase.ReplaceMemberCapabilitiesUseCase;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class ReplaceMemberCapabilitiesUseCaseImpl implements ReplaceMemberCapabilitiesUseCase {
  private final ReplaceRankingMemberCapabilities memberships;
  public ReplaceMemberCapabilitiesUseCaseImpl(ReplaceRankingMemberCapabilities memberships) {
    this.memberships = Objects.requireNonNull(memberships, "memberships");
  }
  @Override public void execute(RankingActor actor, UUID rankingId, UUID memberUserId, Set<RankingCapability> capabilities) {
    if (actor == null || !actor.accountEnabled() || rankingId == null || memberUserId == null)
      throw new RankingAccessDeniedException();
    if (capabilities == null || capabilities.stream().anyMatch(Objects::isNull))
      throw new InvalidRankingCapabilitiesException();
    var outcome = memberships.replace(rankingId, actor.userId(), memberUserId, Set.copyOf(capabilities));
    if (outcome == null || outcome == ReplaceRankingMemberCapabilities.Outcome.NOT_FOUND)
      throw new RankingAccessDeniedException();
    if (outcome == ReplaceRankingMemberCapabilities.Outcome.OWNER_CAPABILITIES_IMMUTABLE)
      throw new RankingOwnerCapabilitiesImmutableException();
  }
}
