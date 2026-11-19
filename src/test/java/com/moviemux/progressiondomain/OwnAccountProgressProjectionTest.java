package com.moviemux.progressiondomain;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.moviemux.progression.domain.model.*;
import java.math.BigInteger;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class OwnAccountProgressProjectionTest {
  private static final UUID ACCOUNT = new UUID(0, 1);

  // Explicit test fixtures only; no selectable production curve is introduced.
  private static ProgressionPolicy policy(String version, long start, BigInteger factor) {
    return new ProgressionPolicy() {
      public String version() { return version; }
      public long startingLevel() { return start; }
      public BigInteger thresholdAt(long level) {
        BigInteger n = BigInteger.valueOf(level - start);
        return n.multiply(n.add(BigInteger.ONE)).multiply(factor);
      }
    };
  }

  @Test
  void wrongAccountIsRejectedBeforeAnyPolicyAccess() {
    var policy = mock(ProgressionPolicy.class);
    var snapshot = new XpLedgerProjection(new UUID(0, 2), BigInteger.TEN, 1);
    assertThatThrownBy(() -> OwnAccountProgressProjection.project(ACCOUNT, snapshot, policy))
        .isInstanceOf(IllegalArgumentException.class);
    verifyNoInteractions(policy);
  }

  @Test
  void missingInputsFailWithoutPolicyAccess() {
    var policy = mock(ProgressionPolicy.class);
    var empty = new XpLedgerProjection(ACCOUNT, BigInteger.ZERO, 0);
    assertThatNullPointerException().isThrownBy(() -> OwnAccountProgressProjection.project(null, empty, policy));
    assertThatNullPointerException().isThrownBy(() -> OwnAccountProgressProjection.project(ACCOUNT, null, policy));
    assertThatNullPointerException().isThrownBy(() -> OwnAccountProgressProjection.project(ACCOUNT, empty, null));
    verifyNoInteractions(policy);
  }

  @Test
  void emptySnapshotUsesOnlyExplicitStartingLevelAndMintsNothing() {
    var empty = new XpLedgerProjection(ACCOUNT, BigInteger.ZERO, 0);
    var progress = OwnAccountProgressProjection.project(ACCOUNT, empty, policy("fixture", 7, BigInteger.TEN));
    assertThat(progress).isEqualTo(new LevelProgress("fixture", 7, BigInteger.ZERO, BigInteger.ZERO, BigInteger.valueOf(20)));
    assertThat(empty.totalXp()).isZero();
    assertThat(empty.awardCount()).isZero();
  }

  @Test
  void sameTotalFromDifferentEventsAndCountsHasIdenticalProjection() {
    var first = XpLedgerProjection.replay(ACCOUNT, List.of(
        new XpAward(ACCOUNT, new XpEventIdentity("fixture-source-a", new UUID(0, 11)), "award-old", BigInteger.valueOf(12))));
    var second = XpLedgerProjection.replay(ACCOUNT, List.of(
        new XpAward(ACCOUNT, new XpEventIdentity("fixture-source-b", new UUID(0, 21)), "award-v2", BigInteger.valueOf(5)),
        new XpAward(ACCOUNT, new XpEventIdentity("fixture-source-c", new UUID(0, 22)), "award-v3", BigInteger.valueOf(7))));
    assertThat(first.awardCount()).isNotEqualTo(second.awardCount());
    var policy = policy("level-policy", 1, BigInteger.ONE);
    assertThat(OwnAccountProgressProjection.project(ACCOUNT, first, policy))
        .isEqualTo(OwnAccountProgressProjection.project(ACCOUNT, second, policy));
  }

  @Test
  void hugeXpRemainsExactAndPoliciesDoNotRewriteTheSnapshot() {
    long index = 4_000_000_000L;
    BigInteger n = BigInteger.valueOf(index);
    BigInteger total = n.multiply(n.add(BigInteger.ONE)).add(BigInteger.ONE);
    var snapshot = new XpLedgerProjection(ACCOUNT, total, 1);
    var first = OwnAccountProgressProjection.project(ACCOUNT, snapshot, policy("first", 0, BigInteger.ONE));
    var second = OwnAccountProgressProjection.project(ACCOUNT, snapshot, policy("second", 3, BigInteger.TWO));
    assertThat(total).isGreaterThan(BigInteger.valueOf(Long.MAX_VALUE));
    assertThat(first.level()).isEqualTo(index);
    assertThat(first.xpIntoLevel()).isEqualTo(BigInteger.ONE);
    assertThat(first.totalXp()).isEqualTo(total);
    assertThat(second.totalXp()).isEqualTo(total);
    assertThat(second.policyVersion()).isEqualTo("second");
    assertThat(second.level()).isNotEqualTo(first.level());
    assertThat(snapshot).isEqualTo(new XpLedgerProjection(ACCOUNT, total, 1));
  }

  @Test
  void invalidExplicitPolicyFailsInsteadOfSelectingFallback() {
    var invalid = policy("invalid", 0, BigInteger.ZERO);
    var snapshot = new XpLedgerProjection(ACCOUNT, BigInteger.TEN, 1);
    assertThatThrownBy(() -> OwnAccountProgressProjection.project(ACCOUNT, snapshot, invalid))
        .isInstanceOf(IllegalArgumentException.class);
    var failing = mock(ProgressionPolicy.class);
    var failure = new IllegalStateException("policy unavailable");
    when(failing.version()).thenThrow(failure);
    assertThatThrownBy(() -> OwnAccountProgressProjection.project(ACCOUNT, snapshot, failing)).isSameAs(failure);
    verify(failing).version();
    verifyNoMoreInteractions(failing);
  }
}
