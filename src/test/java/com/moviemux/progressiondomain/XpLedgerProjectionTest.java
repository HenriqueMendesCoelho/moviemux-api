package com.moviemux.progressiondomain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.moviemux.progression.domain.model.XpAward;
import com.moviemux.progression.domain.model.XpEventIdentity;
import com.moviemux.progression.domain.model.XpLedgerProjection;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class XpLedgerProjectionTest {
  private static final UUID ACCOUNT = UUID.fromString("00000000-0000-0000-0000-000000000001");
  private static final XpEventIdentity EVENT = new XpEventIdentity("test-action", UUID.fromString("00000000-0000-0000-0000-000000000002"));

  private static XpAward award(XpEventIdentity event, String version, BigInteger amount) {
    return new XpAward(ACCOUNT, event, version, amount);
  }

  @Test
  void emptyHistoryDoesNotMintStarterOrBackfillXp() {
    assertThat(XpLedgerProjection.replay(ACCOUNT, List.of()))
        .isEqualTo(new XpLedgerProjection(ACCOUNT, BigInteger.ZERO, 0));
  }

  @Test
  void exactDuplicateDeliveryIsIdempotent() {
    XpAward first = award(EVENT, "v1", BigInteger.TEN);
    XpAward copy = award(new XpEventIdentity(EVENT.source(), EVENT.eventId()), "v1", BigInteger.TEN);
    assertThat(XpLedgerProjection.replay(ACCOUNT, List.of(first, copy, first)))
        .isEqualTo(new XpLedgerProjection(ACCOUNT, BigInteger.TEN, 1));
  }

  @Test
  void sameEventCannotBeRewardedAgainByChangingAmountOrPolicyVersion() {
    XpAward first = award(EVENT, "v1", BigInteger.TEN);
    for (XpAward conflicting : List.of(award(EVENT, "v1", BigInteger.ONE), award(EVENT, "v2", BigInteger.TEN))) {
      assertThatThrownBy(() -> XpLedgerProjection.replay(ACCOUNT, List.of(first, conflicting)))
          .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Conflicting receipt");
    }
  }

  @Test
  void namespacesDistinguishDifferentAuthoritativeEventSources() {
    var other = new XpEventIdentity("another-test-action", EVENT.eventId());
    assertThat(XpLedgerProjection.replay(ACCOUNT, List.of(award(EVENT, "v1", BigInteger.ONE), award(other, "v1", BigInteger.TWO))))
        .isEqualTo(new XpLedgerProjection(ACCOUNT, BigInteger.valueOf(3), 2));
  }

  @Test
  void distinctEventsWithDifferentAcceptedPoliciesRetainTheirExactAmounts() {
    var second = new XpEventIdentity(EVENT.source(), UUID.fromString("00000000-0000-0000-0000-000000000003"));
    BigInteger huge = BigInteger.valueOf(Long.MAX_VALUE).pow(3);
    assertThat(XpLedgerProjection.replay(ACCOUNT, List.of(award(EVENT, "old", huge), award(second, "new", BigInteger.ONE))))
        .isEqualTo(new XpLedgerProjection(ACCOUNT, huge.add(BigInteger.ONE), 2));
  }

  @Test
  void replayOrderDoesNotMatterAndInputsAreNotMutated() {
    var awards = new ArrayList<XpAward>();
    for (int i = 0; i < 100; i++) {
      var next = award(new XpEventIdentity("test", new UUID(0, i)), "v", BigInteger.valueOf(i + 1));
      awards.add(next);
      awards.add(next);
    }
    var original = List.copyOf(awards);
    var result = XpLedgerProjection.replay(ACCOUNT, awards);
    assertThat(awards).containsExactlyElementsOf(original);
    Collections.reverse(awards);
    assertThat(XpLedgerProjection.replay(ACCOUNT, awards)).isEqualTo(result);
    assertThat(result.totalXp()).isEqualTo(BigInteger.valueOf(5050));
    assertThat(result.awardCount()).isEqualTo(100);
  }

  @Test
  void foreignAccountIsRejectedEvenWithMatchingEvent() {
    XpAward other = new XpAward(new UUID(0, 9), EVENT, "v1", BigInteger.TEN);
    assertThatThrownBy(() -> XpLedgerProjection.replay(ACCOUNT, List.of(award(EVENT, "v1", BigInteger.TEN), other)))
        .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("another progression account");
  }

  @Test
  void nullInputsAndNullReceiptsAreRejected() {
    assertThatNullPointerException().isThrownBy(() -> XpLedgerProjection.replay(null, List.of()));
    assertThatNullPointerException().isThrownBy(() -> XpLedgerProjection.replay(ACCOUNT, null));
    assertThatNullPointerException().isThrownBy(() -> XpLedgerProjection.replay(ACCOUNT, java.util.Arrays.asList((XpAward) null)));
  }

  @ParameterizedTest
  @ValueSource(strings = {"", " ", "\t", " leading", "trailing "})
  void eventSourceMustBeAnUnambiguousNonblankIdentity(String source) {
    assertThatThrownBy(() -> new XpEventIdentity(source, EVENT.eventId())).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void eventIdentityRequiresBothParts() {
    assertThatNullPointerException().isThrownBy(() -> new XpEventIdentity(null, EVENT.eventId()));
    assertThatNullPointerException().isThrownBy(() -> new XpEventIdentity("source", null));
  }

  @Test
  void receiptsRejectMissingIdentityPolicyOrNonpositiveAwards() {
    assertThatNullPointerException().isThrownBy(() -> new XpAward(null, EVENT, "v", BigInteger.ONE));
    assertThatNullPointerException().isThrownBy(() -> award(null, "v", BigInteger.ONE));
    assertThatNullPointerException().isThrownBy(() -> award(EVENT, null, BigInteger.ONE));
    assertThatNullPointerException().isThrownBy(() -> award(EVENT, "v", null));
    assertThatThrownBy(() -> award(EVENT, " ", BigInteger.ONE)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> award(EVENT, "v", BigInteger.ZERO)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> award(EVENT, "v", BigInteger.valueOf(-1))).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void impossibleProjectionSnapshotsAreRejected() {
    assertThatNullPointerException().isThrownBy(() -> new XpLedgerProjection(null, BigInteger.ZERO, 0));
    assertThatNullPointerException().isThrownBy(() -> new XpLedgerProjection(ACCOUNT, null, 0));
    assertThatThrownBy(() -> new XpLedgerProjection(ACCOUNT, BigInteger.valueOf(-1), 1)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new XpLedgerProjection(ACCOUNT, BigInteger.ONE, -1)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new XpLedgerProjection(ACCOUNT, BigInteger.ONE, 0)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new XpLedgerProjection(ACCOUNT, BigInteger.ZERO, 1)).isInstanceOf(IllegalArgumentException.class);
  }
}
