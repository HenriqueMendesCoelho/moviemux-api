package com.moviemux.progression.domain.model;

import java.math.BigInteger;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Pure threshold lookup, without a default economy or a product level cap. BigInteger avoids XP
 * overflow; levels use nonnegative long indices. A result that needs an unrepresentable next level
 * fails explicitly instead of wrapping. Lookup is logarithmic in the level index.
 *
 * <p>Checks visited neighborhoods. These checks cannot prove an arbitrary supplied function's global
 * monotonicity: a policy implementation must separately satisfy ProgressionPolicy's full contract.
 */
public final class LevelProgressCalculator {
  private final ProgressionPolicy policy;

  public LevelProgressCalculator(ProgressionPolicy policy) {
    this.policy = Objects.requireNonNull(policy, "policy");
  }

  public LevelProgress calculate(BigInteger totalXp) {
    Objects.requireNonNull(totalXp, "totalXp");
    if (totalXp.signum() < 0) throw new IllegalArgumentException("XP must be nonnegative");
    String version = Objects.requireNonNull(policy.version(), "policy.version");
    long start = policy.startingLevel();
    if (version.isBlank() || start < 0 || start == Long.MAX_VALUE) {
      throw new IllegalArgumentException("Invalid progression policy identity or starting level");
    }
    var thresholds = new Thresholds(start);
    if (thresholds.at(start).signum() != 0) {
      throw new IllegalArgumentException("Initial threshold must be zero");
    }
    long lower = start;
    long upper = start + 1;
    while (thresholds.checked(upper).compareTo(totalXp) <= 0) {
      if (upper == Long.MAX_VALUE) {
        throw new ArithmeticException("Next level exceeds supported level index");
      }
      lower = upper;
      long distance = upper - start;
      long remaining = Long.MAX_VALUE - upper;
      upper += Math.min(distance, remaining);
    }
    while (upper - lower > 1) {
      long middle = lower + (upper - lower) / 2;
      if (thresholds.checked(middle).compareTo(totalXp) <= 0) {
        lower = middle;
      } else {
        upper = middle;
      }
    }
    BigInteger current = thresholds.checked(lower);
    BigInteger next = thresholds.checked(upper);
    return new LevelProgress(version, lower, totalXp, totalXp.subtract(current), next.subtract(totalXp));
  }

  private final class Thresholds {
    private final long start;
    private final Map<Long, BigInteger> values = new HashMap<>();

    private Thresholds(long start) {
      this.start = start;
    }

    private BigInteger at(long level) {
      return values.computeIfAbsent(level, key -> {
        BigInteger value = Objects.requireNonNull(policy.thresholdAt(key), "policy threshold");
        if (value.signum() < 0) throw new IllegalArgumentException("Negative progression threshold");
        return value;
      });
    }

    private BigInteger checked(long level) {
      BigInteger current = at(level);
      if (level > start) {
        BigInteger previous = at(level - 1);
        BigInteger cost = current.subtract(previous);
        if (cost.signum() <= 0) throw new IllegalArgumentException("Nonpositive advancement cost");
        if (level - start > 1) {
          BigInteger previousCost = previous.subtract(at(level - 2));
          if (previousCost.signum() <= 0 || cost.compareTo(previousCost) <= 0) {
            throw new IllegalArgumentException("Advancement costs must strictly increase");
          }
        }
      }
      return current;
    }
  }
}
