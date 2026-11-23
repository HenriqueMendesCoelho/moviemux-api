package com.moviemux.rankingcontent.domain.port;

import com.moviemux.rankingcontent.domain.model.OpenedRatingCopyPreview;

public interface OpenOwnRatingCopyPreview {
  /** Authenticate and decode material without performing expiry or authorization checks. */
  OpenedRatingCopyPreview open(String token);
}
