package com.moviemux.ranking.domain.usecase.impl;

import com.moviemux.ranking.domain.exception.InvalidRankingAccessPageException;
import com.moviemux.ranking.domain.exception.InvalidRankingCapabilitiesException;
import com.moviemux.ranking.domain.exception.RankingAccessDeniedException;
import com.moviemux.ranking.domain.model.MembershipRosterPage;
import com.moviemux.ranking.domain.model.RankingActor;
import com.moviemux.ranking.domain.model.RankingCapability;
import com.moviemux.ranking.domain.port.RankingMembershipRoster;
import com.moviemux.ranking.domain.usecase.ManageMembershipRosterUseCase;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(isolation = Isolation.READ_COMMITTED)
public class ManageMembershipRosterUseCaseImpl implements ManageMembershipRosterUseCase {
  private final RankingMembershipRoster roster;

  public ManageMembershipRosterUseCaseImpl(RankingMembershipRoster roster) {
    this.roster = Objects.requireNonNull(roster, "roster");
  }

  @Override @Transactional(readOnly = true, isolation = Isolation.READ_COMMITTED)
  public MembershipRosterPage list(RankingActor actor, UUID rankingId, UUID after, int limit) {
    var owner = actorId(actor, rankingId);
    if (limit < 1 || limit > MAX_LIMIT) throw new InvalidRankingAccessPageException();
    var loaded = roster.find(rankingId, owner, after, limit + 1);
    if (loaded == null || loaded.size() > limit + 1) throw new RankingAccessDeniedException();
    var previous = after;
    // PostgreSQL UUID order is unsigned by byte. Do not compare Java's signed UUID parts.
    for (var member : loaded) {
      if (member == null || (previous != null && previous.toString().compareTo(member.membershipId().toString()) >= 0))
        throw new RankingAccessDeniedException();
      previous = member.membershipId();
    }
    var items = loaded.subList(0, Math.min(limit, loaded.size()));
    return new MembershipRosterPage(items, loaded.size() > limit ? items.getLast().membershipId() : null);
  }

  @Override public void replace(RankingActor actor, UUID rankingId, UUID membershipId, Set<RankingCapability> capabilities) {
    var owner = actorId(actor, rankingId);
    if (membershipId == null) throw new RankingAccessDeniedException();
    if (capabilities == null || capabilities.stream().anyMatch(Objects::isNull))
      throw new InvalidRankingCapabilitiesException();
    if (!roster.replace(rankingId, owner, membershipId, Set.copyOf(capabilities)))
      throw new RankingAccessDeniedException();
  }

  private static UUID actorId(RankingActor actor, UUID rankingId) {
    if (actor == null || !actor.accountEnabled() || rankingId == null) throw new RankingAccessDeniedException();
    return actor.userId();
  }
}
