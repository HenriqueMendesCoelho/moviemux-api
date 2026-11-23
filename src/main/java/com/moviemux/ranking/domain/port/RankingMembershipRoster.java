package com.moviemux.ranking.domain.port;

import com.moviemux.ranking.domain.model.RankingCapability;
import com.moviemux.ranking.domain.model.RosterMember;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public interface RankingMembershipRoster {
  /** Current owner only. Empty means authorized with no members on this page. */
  List<RosterMember> find(UUID rankingId, UUID actorId, UUID after, int maxRows);

  /** Revalidate ownership and resolve this live membership in the same transaction as replacement. */
  boolean replace(UUID rankingId, UUID actorId, UUID membershipId, Set<RankingCapability> capabilities);
}
