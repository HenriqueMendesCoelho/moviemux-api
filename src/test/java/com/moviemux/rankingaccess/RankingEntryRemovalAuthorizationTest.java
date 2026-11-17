package com.moviemux.rankingaccess;

import static org.assertj.core.api.Assertions.*;
import com.moviemux.ranking.domain.exception.RankingAccessDeniedException;
import com.moviemux.ranking.domain.model.*;
import com.moviemux.ranking.domain.service.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class RankingEntryRemovalAuthorizationTest {
  private final UUID rank=UUID.randomUUID(),entry=UUID.randomUUID(),owner=UUID.randomUUID(),member=UUID.randomUUID();
  private RankingEntryRemovalAuthorization policy(UUID actor,Set<RankingCapability> grants,Optional<UUID> creator,boolean active,boolean archived) {
    var snapshot=new RankingAccessSnapshot(new RankingIdentity(rank,owner,RankingVisibility.PRIVATE,RankingAdmission.INVITATION,archived),
        Optional.of(new RankingMembership(rank,actor,active,grants)));
    return new RankingEntryRemovalAuthorization(new RankingAuthorization((r,a)->Optional.of(snapshot)),
        (r,e)->Optional.of(new RankingEntryRemovalTarget(entry,rank,creator)));
  }
  @Test void ownCapabilityAllowsOnlyOwnAuthoritativeEntries() {
    var actor=new RankingActor(member,true);
    assertThatCode(()->policy(member,Set.of(RankingCapability.REMOVE_OWN_ENTRY),Optional.of(member),true,false).require(actor,rank,entry)).doesNotThrowAnyException();
    for(var creator:List.of(Optional.of(owner),Optional.<UUID>empty()))
      assertThatThrownBy(()->policy(member,Set.of(RankingCapability.REMOVE_OWN_ENTRY),creator,true,false).require(actor,rank,entry)).isInstanceOf(RankingAccessDeniedException.class);
  }
  @Test void broadCapabilityAndOwnerCoverOwnOtherAndOrphanEntries() {
    for(var creator:List.of(Optional.of(member),Optional.of(owner),Optional.<UUID>empty())) {
      assertThatCode(()->policy(member,Set.of(RankingCapability.REMOVE_ENTRY),creator,true,false).require(new RankingActor(member,true),rank,entry)).doesNotThrowAnyException();
      assertThatCode(()->policy(owner,Set.of(),creator,true,false).require(new RankingActor(owner,true),rank,entry)).doesNotThrowAnyException();
    }
  }
  @Test void globalAdminHasNoBypassAndOtherLocalCapabilitiesDoNotImplyRemoval() {
    // There is deliberately no global role input: an instance admin must meet the same local policy.
    assertThatThrownBy(()->policy(member,Set.of(RankingCapability.READ,RankingCapability.MANAGE_MEMBERS,RankingCapability.ADD_ENTRY),Optional.of(member),true,false)
        .require(new RankingActor(member,true),rank,entry)).isInstanceOf(RankingAccessDeniedException.class);
    var outsider=new RankingEntryRemovalAuthorization(new RankingAuthorization((r,a)->Optional.of(
        new RankingAccessSnapshot(new RankingIdentity(rank,owner,RankingVisibility.PUBLIC,RankingAdmission.OPEN),Optional.empty()))),
        (r,e)->Optional.of(new RankingEntryRemovalTarget(entry,rank,Optional.of(member))));
    assertThatThrownBy(()->outsider.require(new RankingActor(member,true),rank,entry)).isInstanceOf(RankingAccessDeniedException.class);
  }
  @Test void rejectsDisabledInactiveArchivedAndWrongScopeBeforeAnyRemoval() {
    var actor=new RankingActor(member,true);
    assertThatThrownBy(()->policy(member,Set.of(RankingCapability.REMOVE_ENTRY),Optional.of(member),false,false).require(actor,rank,entry)).isInstanceOf(RankingAccessDeniedException.class);
    for(UUID user:List.of(owner,member))
      assertThatThrownBy(()->policy(user,Set.of(RankingCapability.REMOVE_ENTRY),Optional.of(user),true,true).require(new RankingActor(user,true),rank,entry)).isInstanceOf(RankingAccessDeniedException.class);
    var policy=policy(member,Set.of(RankingCapability.REMOVE_ENTRY),Optional.of(member),true,false);
    assertThatThrownBy(()->policy.require(new RankingActor(member,false),rank,entry)).isInstanceOf(RankingAccessDeniedException.class);
    assertThatThrownBy(()->policy.require(actor,UUID.randomUUID(),entry)).isInstanceOf(RankingAccessDeniedException.class);
    assertThatThrownBy(()->policy.require(actor,rank,UUID.randomUUID())).isInstanceOf(RankingAccessDeniedException.class);
    assertThatThrownBy(()->policy.require(null,rank,entry)).isInstanceOf(RankingAccessDeniedException.class);
  }
  @Test void entryLookupIsRankingScopedEvenWhenEntryIdentifiersRepeat() {
    UUID otherRank=UUID.randomUUID();
    var actor=new RankingActor(member,true);
    var snapshot=new RankingAccessSnapshot(new RankingIdentity(rank,owner,RankingVisibility.PRIVATE,RankingAdmission.INVITATION),
        Optional.of(new RankingMembership(rank,member,true,Set.of(RankingCapability.REMOVE_OWN_ENTRY))));
    var policy=new RankingEntryRemovalAuthorization(new RankingAuthorization((r,a)->Optional.of(
        r.equals(rank)?snapshot:new RankingAccessSnapshot(new RankingIdentity(otherRank,owner,RankingVisibility.PRIVATE,RankingAdmission.INVITATION),
            Optional.of(new RankingMembership(otherRank,member,true,Set.of(RankingCapability.REMOVE_OWN_ENTRY)))))),(requestedRanking,requestedEntry)->{
      assertThat(requestedEntry).isEqualTo(entry);
      if(requestedRanking.equals(rank))return Optional.of(new RankingEntryRemovalTarget(entry,rank,Optional.of(member)));
      assertThat(requestedRanking).isEqualTo(otherRank);
      return Optional.of(new RankingEntryRemovalTarget(entry,otherRank,Optional.of(owner)));
    });
    assertThatCode(()->policy.require(actor,rank,entry)).doesNotThrowAnyException();
    assertThatThrownBy(()->policy.require(actor,otherRank,entry)).isInstanceOf(RankingAccessDeniedException.class);
  }

  @Test void archivedAlternativeAuthorizationCannotUseWriteGrantWhenReadIsAbsent() {
    var snapshot=new RankingAccessSnapshot(new RankingIdentity(rank,owner,RankingVisibility.PRIVATE,RankingAdmission.INVITATION,true),
        Optional.of(new RankingMembership(rank,member,true,Set.of(RankingCapability.REMOVE_OWN_ENTRY))));
    var authorization=new RankingAuthorization((r,a)->Optional.of(snapshot));
    assertThatThrownBy(()->authorization.authorizeAny(new RankingActor(member,true),rank,
        Set.of(RankingCapability.READ,RankingCapability.REMOVE_OWN_ENTRY))).isInstanceOf(RankingAccessDeniedException.class);
    assertThatThrownBy(()->authorization.authorizeAny(new RankingActor(member,true),rank,Set.of())).isInstanceOf(RankingAccessDeniedException.class);
  }

  @Test void invalidAlternativeSetsFailBeforeReadingCurrentAccess() {
    var authorization=new RankingAuthorization((r,a)->{throw new AssertionError("Invalid capability request must not query access");});
    var actor=new RankingActor(member,true);
    assertThatThrownBy(()->authorization.authorizeAny(actor,rank,null)).isInstanceOf(RankingAccessDeniedException.class);
    var alternatives=new HashSet<RankingCapability>();alternatives.add(RankingCapability.REMOVE_ENTRY);alternatives.add(null);
    assertThatThrownBy(()->authorization.authorizeAny(actor,rank,alternatives)).isInstanceOf(RankingAccessDeniedException.class);
  }

  @Test void nullEntryScopeFailsBeforeEitherPortAndResourceScopeIsAlwaysChecked() {
    var authorization=new RankingAuthorization((r,a)->{throw new AssertionError("Invalid scope must not query access");});
    var policy=new RankingEntryRemovalAuthorization(authorization,(r,e)->{throw new AssertionError("Invalid scope must not query entry");});
    var actor=new RankingActor(member,true);
    assertThatThrownBy(()->policy.require(actor,null,entry)).isInstanceOf(RankingAccessDeniedException.class);
    assertThatThrownBy(()->policy.require(actor,rank,null)).isInstanceOf(RankingAccessDeniedException.class);
    assertThatThrownBy(()->authorization.requireResource(actor,null,rank,RankingCapability.REMOVE_ENTRY)).isInstanceOf(RankingAccessDeniedException.class);
    assertThatThrownBy(()->authorization.requireResource(actor,rank,null,RankingCapability.REMOVE_ENTRY)).isInstanceOf(RankingAccessDeniedException.class);
    assertThatThrownBy(()->authorization.requireResource(actor,rank,UUID.randomUUID(),RankingCapability.REMOVE_ENTRY)).isInstanceOf(RankingAccessDeniedException.class);
  }

  @Test void matchingResourceScopeStillRequiresCurrentLocalAuthorization() {
    var snapshot=new RankingAccessSnapshot(new RankingIdentity(rank,owner,RankingVisibility.PRIVATE,RankingAdmission.INVITATION),
        Optional.of(new RankingMembership(rank,member,true,Set.of(RankingCapability.REMOVE_ENTRY))));
    var reads=new AtomicInteger();
    var authorization=new RankingAuthorization((r,a)->{reads.incrementAndGet();return Optional.of(snapshot);});
    assertThatCode(()->authorization.requireResource(new RankingActor(member,true),rank,rank,RankingCapability.REMOVE_ENTRY)).doesNotThrowAnyException();
    assertThat(reads.get()).isEqualTo(1);
  }

  @Test void missingOrInvalidEntryPortFailsClosedAndAlternativeCheckReadsAccessOnce() {
    var reads=new AtomicInteger();
    var snapshot=new RankingAccessSnapshot(new RankingIdentity(rank,owner,RankingVisibility.PRIVATE,RankingAdmission.INVITATION),
        Optional.of(new RankingMembership(rank,member,true,Set.of(RankingCapability.REMOVE_OWN_ENTRY))));
    var auth=new RankingAuthorization((r,a)->{reads.incrementAndGet();return Optional.of(snapshot);});
    var actor=new RankingActor(member,true);
    new RankingEntryRemovalAuthorization(auth,(r,e)->Optional.of(new RankingEntryRemovalTarget(entry,rank,Optional.of(member)))).require(actor,rank,entry);
    assertThat(reads.get()).isEqualTo(1);
    assertThatThrownBy(()->new RankingEntryRemovalAuthorization(auth,(r,e)->Optional.empty()).require(actor,rank,entry)).isInstanceOf(RankingAccessDeniedException.class);
    assertThatThrownBy(()->new RankingEntryRemovalAuthorization(auth,(r,e)->null).require(actor,rank,entry)).isInstanceOf(RankingAccessDeniedException.class);
    assertThat(reads.get()).isEqualTo(1);
  }
}
