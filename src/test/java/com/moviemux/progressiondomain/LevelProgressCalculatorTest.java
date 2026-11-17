package com.moviemux.progressiondomain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.moviemux.progression.domain.model.LevelProgress;
import com.moviemux.progression.domain.model.LevelProgressCalculator;
import com.moviemux.progression.domain.model.ProgressionPolicy;
import java.math.BigInteger;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongFunction;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class LevelProgressCalculatorTest {
  // Test-only curves are deliberately not exposed as selectable production policies.
  private static ProgressionPolicy policy(long start, LongFunction<BigInteger> threshold) {
    return new ProgressionPolicy() {
      public String version() { return "test-fixture-v3"; }
      public long startingLevel() { return start; }
      public BigInteger thresholdAt(long level) { return threshold.apply(level); }
    };
  }

  private static BigInteger triangularTwice(long offset) {
    BigInteger n = BigInteger.valueOf(offset);
    return n.multiply(n.add(BigInteger.ONE));
  }

  @ParameterizedTest
  @ValueSource(longs = {0, 1, 7, 100})
  void startingLevelAndCurveAreExplicitInputs(long start) {
    var calculator = new LevelProgressCalculator(policy(start, l -> triangularTwice(l - start)));
    assertThat(calculator.calculate(BigInteger.ZERO))
        .isEqualTo(new LevelProgress("test-fixture-v3", start, BigInteger.ZERO, BigInteger.ZERO, BigInteger.TWO));
    assertThat(calculator.calculate(BigInteger.valueOf(6)).level()).isEqualTo(start + 2);
  }

  @Test
  void exactThresholdBeforeAndAfterEachBoundaryMatchIndependentLinearOracle() {
    var calculator = new LevelProgressCalculator(policy(3, l -> triangularTwice(l - 3)));
    for (int xp = 0; xp < 10_000; xp++) {
      long offset = 0;
      while (triangularTwice(offset + 1).compareTo(BigInteger.valueOf(xp)) <= 0) offset++;
      var progress = calculator.calculate(BigInteger.valueOf(xp));
      assertThat(progress.level()).isEqualTo(3 + offset);
      assertThat(progress.totalXp()).isEqualTo(BigInteger.valueOf(xp));
      assertThat(progress.xpIntoLevel()).isEqualTo(BigInteger.valueOf(xp).subtract(triangularTwice(offset)));
      assertThat(progress.xpToNextLevel()).isEqualTo(triangularTwice(offset + 1).subtract(BigInteger.valueOf(xp)));
      assertThat(progress.policyVersion()).isEqualTo("test-fixture-v3");
    }
  }

  @Test
  void differentNonlinearCurveUsesTheSameCalculatorWithoutAnEconomyDefault() {
    var calculator = new LevelProgressCalculator(policy(0, l -> {
      BigInteger n = BigInteger.valueOf(l);
      return n.multiply(n.add(BigInteger.ONE)).multiply(n.add(BigInteger.TWO));
    }));
    assertThat(calculator.calculate(BigInteger.valueOf(23)))
        .isEqualTo(new LevelProgress("test-fixture-v3", 1, BigInteger.valueOf(23), BigInteger.valueOf(17), BigInteger.ONE));
    assertThat(calculator.calculate(BigInteger.valueOf(24)).level()).isEqualTo(2);
  }

  @Test
  void hugeXpAndManyLevelsUseExactBoundedSearchRatherThanLinearAdvancement() {
    AtomicInteger calls = new AtomicInteger();
    long target = 4_000_000_000L;
    var calculator = new LevelProgressCalculator(policy(0, l -> {
      calls.incrementAndGet();
      return triangularTwice(l);
    }));
    BigInteger xp = triangularTwice(target).add(BigInteger.ONE);
    assertThat(xp).isGreaterThan(BigInteger.valueOf(Long.MAX_VALUE));
    var progress = calculator.calculate(xp);
    assertThat(progress.level()).isEqualTo(target);
    assertThat(progress.xpIntoLevel()).isEqualTo(BigInteger.ONE);
    assertThat(calls.get()).isLessThan(250);
  }

  @Test
  void lastRepresentableNextThresholdWorksAndOverflowFailsExplicitly() {
    var calculator = new LevelProgressCalculator(policy(0, LevelProgressCalculatorTest::triangularTwice));
    BigInteger maximumThreshold = triangularTwice(Long.MAX_VALUE);
    var progress = calculator.calculate(maximumThreshold.subtract(BigInteger.ONE));
    assertThat(progress.level()).isEqualTo(Long.MAX_VALUE - 1);
    assertThat(progress.xpToNextLevel()).isEqualTo(BigInteger.ONE);
    assertThatThrownBy(() -> calculator.calculate(maximumThreshold))
        .isInstanceOf(ArithmeticException.class).hasMessageContaining("Next level");
  }

  @Test
  void startingLevelNearLongLimitDoesNotOverflowSearchExpansion() {
    long start = Long.MAX_VALUE - 2;
    var calculator = new LevelProgressCalculator(policy(start, l -> triangularTwice(l - start)));
    assertThat(calculator.calculate(BigInteger.valueOf(5)).level()).isEqualTo(Long.MAX_VALUE - 1);
    assertThatThrownBy(() -> calculator.calculate(BigInteger.valueOf(6))).isInstanceOf(ArithmeticException.class);
  }

  @Test
  void rejectsNullPolicyNullXpAndNegativeXp() {
    assertThatNullPointerException().isThrownBy(() -> new LevelProgressCalculator(null));
    var calculator = new LevelProgressCalculator(policy(0, LevelProgressCalculatorTest::triangularTwice));
    assertThatNullPointerException().isThrownBy(() -> calculator.calculate(null));
    assertThatThrownBy(() -> calculator.calculate(BigInteger.valueOf(-1))).isInstanceOf(IllegalArgumentException.class);
  }

  @ParameterizedTest
  @ValueSource(longs = {-1, Long.MAX_VALUE})
  void invalidStartingLevelFails(long start) {
    assertThatThrownBy(() -> new LevelProgressCalculator(policy(start, l -> BigInteger.ZERO)).calculate(BigInteger.ZERO))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @ParameterizedTest
  @ValueSource(strings = {"", " ", "\t"})
  void blankPolicyVersionFails(String version) {
    var policy = new ProgressionPolicy() {
      public String version() { return version; }
      public long startingLevel() { return 0; }
      public BigInteger thresholdAt(long level) { return triangularTwice(level); }
    };
    assertThatThrownBy(() -> new LevelProgressCalculator(policy).calculate(BigInteger.ZERO))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void nullVersionAndThresholdFail() {
    var nullVersion = new ProgressionPolicy() {
      public String version() { return null; }
      public long startingLevel() { return 0; }
      public BigInteger thresholdAt(long level) { return BigInteger.ZERO; }
    };
    assertThatNullPointerException().isThrownBy(() -> new LevelProgressCalculator(nullVersion).calculate(BigInteger.ZERO));
    assertThatNullPointerException().isThrownBy(() -> new LevelProgressCalculator(policy(0, l -> null)).calculate(BigInteger.ZERO));
  }

  @Test
  void nonzeroInitialThresholdFailsBeforeProgressCanBeProduced() {
    assertThatThrownBy(() -> new LevelProgressCalculator(policy(0, l -> triangularTwice(l).add(BigInteger.ONE)))
        .calculate(BigInteger.TEN)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Initial");
  }

  @Test
  void negativeThresholdFails() {
    assertThatThrownBy(() -> new LevelProgressCalculator(policy(0, l -> BigInteger.valueOf(-1)))
        .calculate(BigInteger.ZERO)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Negative");
  }

  @Test
  void zeroCostFails() {
    assertThatThrownBy(() -> new LevelProgressCalculator(policy(0, l -> BigInteger.ZERO))
        .calculate(BigInteger.ZERO)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Nonpositive");
  }

  @Test
  void flatAndDecreasingCostsAtVisitedLevelsFail() {
    assertThatThrownBy(() -> new LevelProgressCalculator(policy(0, BigInteger::valueOf))
        .calculate(BigInteger.TEN)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("strictly increase");
    assertThatThrownBy(() -> new LevelProgressCalculator(policy(0, l -> switch ((int) l) {
      case 0 -> BigInteger.ZERO;
      case 1 -> BigInteger.TEN;
      default -> BigInteger.valueOf(11);
    })).calculate(BigInteger.TEN)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("strictly increase");
  }

  @Test
  void aNonpositivePreviousCostInAVisitedNeighborhoodFails() {
    assertThatThrownBy(() -> new LevelProgressCalculator(policy(0, l -> switch ((int) l) {
      case 0 -> BigInteger.ZERO;
      case 1 -> BigInteger.ONE;
      case 2 -> BigInteger.valueOf(3);
      case 3 -> BigInteger.valueOf(2);
      default -> BigInteger.TEN;
    })).calculate(BigInteger.valueOf(4))).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void progressRecordRejectsImpossibleValues() {
    assertThatThrownBy(() -> new LevelProgress("", 0, BigInteger.ZERO, BigInteger.ZERO, BigInteger.ONE)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new LevelProgress("v", -1, BigInteger.ZERO, BigInteger.ZERO, BigInteger.ONE)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new LevelProgress("v", 0, BigInteger.valueOf(-1), BigInteger.ZERO, BigInteger.ONE)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new LevelProgress("v", 0, BigInteger.ONE, BigInteger.valueOf(-1), BigInteger.ONE)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new LevelProgress("v", 0, BigInteger.ZERO, BigInteger.ONE, BigInteger.ONE)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new LevelProgress("v", 0, BigInteger.ZERO, BigInteger.ZERO, BigInteger.ZERO)).isInstanceOf(IllegalArgumentException.class);
    assertThatNullPointerException().isThrownBy(() -> new LevelProgress(null, 0, BigInteger.ZERO, BigInteger.ZERO, BigInteger.ONE));
    assertThatNullPointerException().isThrownBy(() -> new LevelProgress("v", 0, null, BigInteger.ZERO, BigInteger.ONE));
    assertThatNullPointerException().isThrownBy(() -> new LevelProgress("v", 0, BigInteger.ZERO, null, BigInteger.ONE));
    assertThatNullPointerException().isThrownBy(() -> new LevelProgress("v", 0, BigInteger.ZERO, BigInteger.ZERO, null));
  }
}
