package com.moviemux.rankingcontent.domain.port;

import com.moviemux.rankingcontent.domain.model.IssuedRatingCopyPreview;
import com.moviemux.rankingcontent.domain.model.RatingCopyPair;
import com.moviemux.rankingcontent.domain.model.RatingCopySourceVersion;
import java.util.UUID;

public interface SealOwnRatingCopyPreview {
  /** Unavailable if the dedicated key or explicit bounded lifetime are invalid or absent. */
  void requireConfigured();

  /** Confidentially bind actor, pair, source version and destination absence; no writes. */
  IssuedRatingCopyPreview seal(UUID actor, RatingCopyPair pair, RatingCopySourceVersion version);
}
