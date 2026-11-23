package com.moviemux.rankingcontent.domain.port;

import com.moviemux.rankingcontent.domain.model.OpenedRatingCopyPreview;
import com.moviemux.rankingcontent.domain.model.OwnRoundRating;
import com.moviemux.rankingcontent.domain.model.RatingCopyContext;
import java.util.UUID;

public interface ApplyOwnRatingCopy {
  /** Independently authorize an exact live target when token verification is unavailable. */
  void requireTarget(UUID actorId, RatingCopyContext target);

  /** Revalidate both scopes under lock before gate/expiry/state, then insert or replay atomically. */
  OwnRoundRating apply(UUID actorId, OpenedRatingCopyPreview command, boolean enabled);
}
