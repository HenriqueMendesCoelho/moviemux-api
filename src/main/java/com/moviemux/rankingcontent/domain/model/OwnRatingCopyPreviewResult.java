package com.moviemux.rankingcontent.domain.model;

import java.time.Instant;

public record OwnRatingCopyPreviewResult(Status status, Integer sourceScore, String previewToken, Instant expiresAt) {
  public enum Status { READY, SOURCE_INELIGIBLE, DESTINATION_EXISTS }
  @Override public String toString() { return "OwnRatingCopyPreviewResult[status=" + status + "]"; }
}
