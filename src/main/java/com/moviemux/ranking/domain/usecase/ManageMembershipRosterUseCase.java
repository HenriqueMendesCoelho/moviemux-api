package com.moviemux.ranking.domain.usecase;

import com.moviemux.ranking.domain.model.MembershipRosterPage;
import com.moviemux.ranking.domain.model.RankingActor;
import com.moviemux.ranking.domain.model.RankingCapability;
import java.util.Set;
import java.util.UUID;

public interface ManageMembershipRosterUseCase {
  int DEFAULT_LIMIT = 20;
  int MAX_LIMIT = 50;

  MembershipRosterPage list(RankingActor actor, UUID rankingId, UUID after, int limit);
  void replace(RankingActor actor, UUID rankingId, UUID membershipId, Set<RankingCapability> capabilities);
}
