package com.moviemux.ranking.domain.usecase;

import com.moviemux.ranking.domain.model.RankingActor;
import java.util.UUID;

public interface LeaveRankingUseCase {
  void execute(RankingActor actor, UUID rankingId);
}
