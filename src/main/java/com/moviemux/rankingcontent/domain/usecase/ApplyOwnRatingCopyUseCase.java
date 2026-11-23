package com.moviemux.rankingcontent.domain.usecase;

import com.moviemux.ranking.domain.model.RankingActor;
import com.moviemux.rankingcontent.domain.model.OwnRoundRating;
import com.moviemux.rankingcontent.domain.model.RatingCopyContext;

public interface ApplyOwnRatingCopyUseCase {
  OwnRoundRating apply(RankingActor actor, RatingCopyContext target, String token);
}
