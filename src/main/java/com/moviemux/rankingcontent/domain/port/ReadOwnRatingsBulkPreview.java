package com.moviemux.rankingcontent.domain.port;

import com.moviemux.rankingcontent.domain.model.BulkOwnRatingCopyPreview.Batch;
import com.moviemux.rankingcontent.domain.model.BulkOwnRatingCopyPreview.Candidate;
import java.util.List;
import java.util.UUID;

@FunctionalInterface
public interface ReadOwnRatingsBulkPreview {
  /** Validate both collections and resolve bounded explicit pairs in one read-only snapshot. */
  List<Candidate> find(UUID actorId, Batch batch);
}
