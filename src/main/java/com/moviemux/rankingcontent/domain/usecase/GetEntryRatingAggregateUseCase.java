package com.moviemux.rankingcontent.domain.usecase;

import com.moviemux.ranking.domain.model.RankingActor;
import com.moviemux.rankingcontent.domain.model.EntryRatingAggregateView;
import java.util.UUID;

public interface GetEntryRatingAggregateUseCase {
  EntryRatingAggregateView get(RankingActor actor, UUID rankingId, UUID entryId);
}
