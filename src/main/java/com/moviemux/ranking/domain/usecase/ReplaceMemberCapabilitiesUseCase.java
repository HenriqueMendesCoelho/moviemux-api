package com.moviemux.ranking.domain.usecase;

import com.moviemux.ranking.domain.model.RankingActor;
import com.moviemux.ranking.domain.model.RankingCapability;
import java.util.Set;
import java.util.UUID;

public interface ReplaceMemberCapabilitiesUseCase {
  void execute(RankingActor actor, UUID rankingId, UUID memberUserId, Set<RankingCapability> capabilities);
}
