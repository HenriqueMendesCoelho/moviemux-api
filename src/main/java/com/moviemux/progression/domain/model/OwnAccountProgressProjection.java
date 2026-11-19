package com.moviemux.progression.domain.model;

import java.util.Objects;
import java.util.UUID;

/**
 * Pure private projection for one account within the current instance. The caller must supply
 * the authenticated account and a trusted local snapshot; this class is not an authorization
 * boundary or storage reader. Neither source events nor award counts enter the returned view.
 * A level policy is always explicit: this class does not select an economy or award XP.
 */
public final class OwnAccountProgressProjection {
  private OwnAccountProgressProjection() {}

  public static LevelProgress project(
      UUID expectedAccountId, XpLedgerProjection snapshot, ProgressionPolicy policy) {
    Objects.requireNonNull(expectedAccountId, "expectedAccountId");
    Objects.requireNonNull(snapshot, "snapshot");
    Objects.requireNonNull(policy, "policy");
    if (!expectedAccountId.equals(snapshot.accountId())) {
      throw new IllegalArgumentException("Progress belongs to another account");
    }
    return new LevelProgressCalculator(policy).calculate(snapshot.totalXp());
  }
}
