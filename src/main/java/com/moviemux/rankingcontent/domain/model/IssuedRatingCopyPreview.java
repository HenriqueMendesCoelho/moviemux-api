package com.moviemux.rankingcontent.domain.model;

import java.time.Instant;

public record IssuedRatingCopyPreview(String token, Instant expiresAt) {
  @Override public String toString() { return "IssuedRatingCopyPreview[redacted]"; }
}
