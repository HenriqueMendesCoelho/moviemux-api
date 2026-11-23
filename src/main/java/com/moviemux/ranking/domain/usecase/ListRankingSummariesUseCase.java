package com.moviemux.ranking.domain.usecase;

import com.moviemux.ranking.domain.model.*;
import java.util.UUID;

public interface ListRankingSummariesUseCase {
  RankingSummaryPage list(RankingActor actor, UUID after, int limit);
}
