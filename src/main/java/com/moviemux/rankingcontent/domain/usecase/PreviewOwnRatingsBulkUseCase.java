package com.moviemux.rankingcontent.domain.usecase;

import com.moviemux.ranking.domain.model.RankingActor;
import com.moviemux.rankingcontent.domain.model.BulkOwnRatingCopyPreview.Batch;
import com.moviemux.rankingcontent.domain.model.BulkOwnRatingCopyPreview.Result;
import java.util.List;

public interface PreviewOwnRatingsBulkUseCase {
  List<Result> preview(RankingActor actor, Batch batch);
}
