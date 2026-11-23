package com.moviemux.rankingcontent.domain.model;

import java.time.Instant;

/** Authoritative owned source fingerprint, never from the request body. */
public record RatingCopySourceVersion(Integer score, Instant createdAt, Instant updatedAt) {
  @Override public String toString() { return "RatingCopySourceVersion[redacted]"; }
}
