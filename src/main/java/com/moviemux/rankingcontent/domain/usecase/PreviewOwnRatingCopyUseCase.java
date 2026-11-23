package com.moviemux.rankingcontent.domain.usecase;

import com.moviemux.ranking.domain.model.RankingActor;
import com.moviemux.rankingcontent.domain.model.OwnRatingCopyPreviewResult;
import com.moviemux.rankingcontent.domain.model.RatingCopyPair;

public interface PreviewOwnRatingCopyUseCase {
  OwnRatingCopyPreviewResult preview(RankingActor actor, RatingCopyPair pair);
}
