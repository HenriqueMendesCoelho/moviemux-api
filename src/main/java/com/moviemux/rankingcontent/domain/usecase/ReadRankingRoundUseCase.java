package com.moviemux.rankingcontent.domain.usecase;

import com.moviemux.ranking.domain.model.RankingActor;
import com.moviemux.rankingcontent.domain.model.RoundRevealView;
import java.util.UUID;

public interface ReadRankingRoundUseCase {
  RoundRevealView read(RankingActor actor, UUID rank, UUID entry, UUID round);
}
