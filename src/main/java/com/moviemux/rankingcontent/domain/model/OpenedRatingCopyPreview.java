package com.moviemux.rankingcontent.domain.model;

import java.time.Instant;
import java.util.UUID;

/** Authenticated claims from a sealed preview. Never log this payload. */
public record OpenedRatingCopyPreview(UUID operationId, UUID actorId, RatingCopyPair pair,
    Instant expiresAt, RatingCopySourceVersion source) {
  @Override public String toString() { return "OpenedRatingCopyPreview[redacted]"; }
}
