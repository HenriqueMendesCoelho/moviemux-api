package com.moviemux.progression.domain.model;

import java.math.BigInteger;
import java.util.Objects;

/** Exact base units, not percentages or an implied reward/rounding policy. */
public record LevelProgress(
    String policyVersion,
    long level,
    BigInteger totalXp,
    BigInteger xpIntoLevel,
    BigInteger xpToNextLevel) {
  public LevelProgress {
    Objects.requireNonNull(policyVersion, "policyVersion");
    Objects.requireNonNull(totalXp, "totalXp");
    Objects.requireNonNull(xpIntoLevel, "xpIntoLevel");
    Objects.requireNonNull(xpToNextLevel, "xpToNextLevel");
    if (policyVersion.isBlank() || level < 0 || totalXp.signum() < 0
        || xpIntoLevel.signum() < 0 || xpToNextLevel.signum() <= 0
        || xpIntoLevel.compareTo(totalXp) > 0) {
      throw new IllegalArgumentException("Invalid level progress");
    }
  }
}
