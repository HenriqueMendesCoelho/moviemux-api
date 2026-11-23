package com.moviemux.rankingcontent.domain.model;

import java.util.List;
import java.util.UUID;

/** Explicit collection-scoped pairs, with no discovery or writing semantics. */
public final class BulkOwnRatingCopyPreview {
  public static final int MAX_ITEMS = 50;

  private BulkOwnRatingCopyPreview() {}

  public record Pairing(UUID sourceEntryId, UUID sourceRoundId, UUID destinationEntryId, UUID destinationRoundId) {}
  public record Batch(UUID sourceRankingId, UUID destinationRankingId, List<Pairing> items) {}
  public enum Status { READY, UNAVAILABLE, DESTINATION_EXISTS, SOURCE_INELIGIBLE }

  public record Candidate(int index, boolean available, boolean destinationExists, Integer ownSourceScore) {
    @Override public String toString() { return "Candidate[index=" + index + ", available=" + available + "]"; }
  }
  public record Result(int index, Status status, Integer sourceScore) {
    @Override public String toString() { return "Result[index=" + index + ", status=" + status + "]"; }
  }
}
