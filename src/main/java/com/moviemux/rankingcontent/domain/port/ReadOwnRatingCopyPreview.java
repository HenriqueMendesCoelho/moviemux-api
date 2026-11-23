package com.moviemux.rankingcontent.domain.port;

import com.moviemux.rankingcontent.domain.model.RatingCopyPair;
import com.moviemux.rankingcontent.domain.model.RatingCopyPreviewSnapshot;
import java.util.Optional;
import java.util.UUID;

public interface ReadOwnRatingCopyPreview {
  /**
   * Read both exact scopes, current authorizations, same canonical film and own rating status
   * in one snapshot. Empty is deliberately indistinguishable from denied; do not mutate.
   */
  Optional<RatingCopyPreviewSnapshot> find(UUID actor, RatingCopyPair pair);
}
