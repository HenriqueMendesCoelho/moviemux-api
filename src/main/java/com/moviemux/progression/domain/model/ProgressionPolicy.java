package com.moviemux.progression.domain.model;

import java.math.BigInteger;

/**
 * An explicitly selected, immutable policy. No production curve or starting level is provided here.
 * Thresholds are cumulative integer XP base units; the initial threshold must be zero and successive
 * positive advancement costs must strictly increase. Implementations must establish these properties
 * for their entire supported domain, not merely for the points visited by a calculation.
 */
public interface ProgressionPolicy {
  String version();

  long startingLevel();

  BigInteger thresholdAt(long level);
}
