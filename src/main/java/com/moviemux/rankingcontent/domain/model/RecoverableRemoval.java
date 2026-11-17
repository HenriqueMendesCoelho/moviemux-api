package com.moviemux.rankingcontent.domain.model;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/** Significant removal only. Membership exit/archive must never create this marker.
 * Persistence must compare the current marker under lock before restore or purge.
 */
public record RecoverableRemoval(Instant removedAt) {
  public static final Duration RECOVERY_WINDOW = Duration.ofHours(24);
  public RecoverableRemoval {
    Objects.requireNonNull(removedAt, "removedAt");
    removedAt.plus(RECOVERY_WINDOW); // Fail before storing an unrepresentable deadline.
  }
  public Instant recoverableUntil() { return removedAt.plus(RECOVERY_WINDOW); }
  public boolean canRestoreAt(Instant now) {
    Objects.requireNonNull(now, "now");
    return !now.isBefore(removedAt) && now.isBefore(recoverableUntil());
  }
  public boolean canPurgeAt(Instant now) {
    return !Objects.requireNonNull(now, "now").isBefore(recoverableUntil());
  }
}
