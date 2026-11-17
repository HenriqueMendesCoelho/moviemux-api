package com.moviemux.rankingdomain;

import static org.assertj.core.api.Assertions.*;

import com.moviemux.ranking.domain.exception.RankingAccessDeniedException;
import com.moviemux.ranking.domain.model.*;
import com.moviemux.ranking.domain.service.RankingAuthorization;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class RankingAuthorizationTest {
  private final UUID a = UUID.randomUUID();
  private final UUID b = UUID.randomUUID();
  private final UUID owner = UUID.randomUUID();
  private final UUID user = UUID.randomUUID();
  private final RankingActor actor = new RankingActor(user, true);

  private RankingIdentity ranking(UUID id) {
    return new RankingIdentity(id, owner, RankingVisibility.PRIVATE, RankingAdmission.INVITATION);
  }

  private RankingAccessSnapshot snapshot(RankingMembership member) {
    return new RankingAccessSnapshot(ranking(member.rankingId()), Optional.of(member));
  }

  private RankingAuthorization policy(RankingAccessSnapshot snapshot) {
    return new RankingAuthorization((rankingId, actorId) -> Optional.of(snapshot));
  }

  @Test
  void newMembersHaveExactlyReadAndOwnRatingPermissions() {
    var member = RankingMembership.newlyAdmitted(a, user);
    var policy = policy(snapshot(member));
    for (var capability : RankingCapability.values()) {
      if (Set.of(RankingCapability.READ, RankingCapability.RATE_OWN).contains(capability)) {
        assertThatCode(() -> policy.require(actor, a, capability)).doesNotThrowAnyException();
      } else {
        assertThatThrownBy(() -> policy.require(actor, a, capability))
            .isInstanceOf(RankingAccessDeniedException.class);
      }
    }
  }

  @Test
  void grantsAreIndependentAcrossRankingsAndDefensivelyCopied() {
    Set<RankingCapability> grants = new HashSet<>(Set.of(RankingCapability.ADD_ENTRY));
    var memberA = new RankingMembership(a, user, true, grants);
    grants.clear();
    var memberB = RankingMembership.newlyAdmitted(b, user);
    var policy = new RankingAuthorization((rank, actorId) ->
        Optional.of(snapshot(rank.equals(a) ? memberA : memberB)));
    assertThatCode(() -> policy.require(actor, a, RankingCapability.ADD_ENTRY))
        .doesNotThrowAnyException();
    assertThatThrownBy(() -> policy.require(actor, b, RankingCapability.ADD_ENTRY))
        .isInstanceOf(RankingAccessDeniedException.class);
    assertThatThrownBy(() -> memberA.capabilities().clear())
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void rechecksMembershipAndGrantsOnEveryOperation() {
    var current = new AtomicReference<>(snapshot(RankingMembership.newlyAdmitted(a, user)));
    var policy = new RankingAuthorization((rank, actorId) -> Optional.of(current.get()));
    policy.require(actor, a, RankingCapability.RATE_OWN);
    current.set(snapshot(new RankingMembership(a, user, true, Set.of(RankingCapability.READ))));
    assertThatThrownBy(() -> policy.require(actor, a, RankingCapability.RATE_OWN))
        .isInstanceOf(RankingAccessDeniedException.class);
    current.set(snapshot(new RankingMembership(a, user, false, Set.of(RankingCapability.READ))));
    assertThatThrownBy(() -> policy.require(actor, a, RankingCapability.READ))
        .isInstanceOf(RankingAccessDeniedException.class);
  }

  @Test
  void doesNotTrustForeignResourceMembershipOrRepositoryScope() {
    var policy = policy(snapshot(RankingMembership.newlyAdmitted(a, user)));
    assertThatThrownBy(() -> policy.requireResource(actor, a, b, RankingCapability.READ))
        .isInstanceOf(RankingAccessDeniedException.class);
    assertThatThrownBy(() -> policy.require(actor, b, RankingCapability.READ))
        .isInstanceOf(RankingAccessDeniedException.class);
    var otherActorPolicy = policy(snapshot(RankingMembership.newlyAdmitted(a, owner)));
    assertThatThrownBy(() -> otherActorPolicy.require(actor, a, RankingCapability.READ))
        .isInstanceOf(RankingAccessDeniedException.class);
    assertThatThrownBy(() -> new RankingAccessSnapshot(ranking(a),
        Optional.of(RankingMembership.newlyAdmitted(b, user))))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void ownerHasLocalCapabilitiesButRequiresEnabledAccountAndActiveMembership() {
    var ownerActor = new RankingActor(owner, true);
    var policy = policy(snapshot(new RankingMembership(a, owner, true, Set.of())));
    for (var capability : RankingCapability.values()) policy.require(ownerActor, a, capability);
    assertThatThrownBy(() -> policy.require(new RankingActor(owner, false), a, RankingCapability.READ))
        .isInstanceOf(RankingAccessDeniedException.class);
    var removed = policy(snapshot(new RankingMembership(a, owner, false, Set.of())));
    assertThatThrownBy(() -> removed.require(ownerActor, a, RankingCapability.READ))
        .isInstanceOf(RankingAccessDeniedException.class);
  }

  @Test
  void absenceAndMalformedRequestsDenyWithoutDisclosingExistence() {
    var missing = new RankingAuthorization((rank, actorId) -> Optional.empty());
    var nonmember = policy(new RankingAccessSnapshot(ranking(a), Optional.empty()));
    for (var policy : List.of(missing, nonmember)) {
      assertThatThrownBy(() -> policy.require(actor, a, RankingCapability.READ))
          .isInstanceOf(RankingAccessDeniedException.class).hasMessage("Ranking access denied");
      assertThatThrownBy(() -> policy.require(null, a, RankingCapability.READ))
          .isInstanceOf(RankingAccessDeniedException.class);
      assertThatThrownBy(() -> policy.require(actor, null, RankingCapability.READ))
          .isInstanceOf(RankingAccessDeniedException.class);
      assertThatThrownBy(() -> policy.require(actor, a, null))
          .isInstanceOf(RankingAccessDeniedException.class);
    }
  }

  @Test
  void visibilityAndAdmissionAreIndependentAndPublicReadNeverGrantsMembership() {
    for (var admission : RankingAdmission.values()) {
      for (var visibility : RankingVisibility.values()) {
        var identity = new RankingIdentity(a, owner, visibility, admission);
        assertThat(RankingAuthorization.allowsPublicProjection(identity))
            .isEqualTo(visibility == RankingVisibility.PUBLIC);
        var policy = policy(new RankingAccessSnapshot(identity, Optional.empty()));
        for (var capability : RankingCapability.values()) {
          assertThatThrownBy(() -> policy.require(actor, a, capability))
              .isInstanceOf(RankingAccessDeniedException.class);
        }
      }
    }
    assertThat(RankingAuthorization.allowsPublicProjection(null)).isFalse();
  }
}
