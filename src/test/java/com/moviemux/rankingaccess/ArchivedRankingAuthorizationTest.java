package com.moviemux.rankingaccess;

import static org.assertj.core.api.Assertions.*;
import com.moviemux.ranking.domain.exception.*;
import com.moviemux.ranking.domain.model.*;
import com.moviemux.ranking.domain.service.RankingAuthorization;
import com.moviemux.ranking.domain.usecase.impl.*;
import com.moviemux.ranking.domain.port.ReplaceRankingMemberCapabilities;
import java.util.*;
import org.junit.jupiter.api.Test;

class ArchivedRankingAuthorizationTest {
  private final UUID rank=UUID.randomUUID(),owner=UUID.randomUUID(),member=UUID.randomUUID();
  private RankingAccessSnapshot snapshot(UUID actor,Set<RankingCapability> grants) {
    return new RankingAccessSnapshot(new RankingIdentity(rank,owner,RankingVisibility.PRIVATE,RankingAdmission.INVITATION,true),
        Optional.of(new RankingMembership(rank,actor,true,grants)));
  }
  @Test void archivedOwnerAndReadableMemberHaveOnlyReadRegardlessOfStoredGrants() {
    for(UUID actor:List.of(owner,member)) {
      var snapshot=snapshot(actor,EnumSet.allOf(RankingCapability.class));
      var auth=new RankingAuthorization((r,a)->Optional.of(snapshot));
      for(var capability:RankingCapability.values()) {
        if(capability==RankingCapability.READ) assertThatCode(()->auth.require(new RankingActor(actor,true),rank,capability)).doesNotThrowAnyException();
        else assertThatThrownBy(()->auth.require(new RankingActor(actor,true),rank,capability)).isInstanceOf(RankingAccessDeniedException.class);
      }
      assertThat(new GetRankingAccessUseCaseImpl(auth).execute(new RankingActor(actor,true),rank).capabilities()).containsExactly(RankingCapability.READ);
      assertThat(new ListRankingAccessUseCaseImpl((a,c,l)->List.of(snapshot)).execute(new RankingActor(actor,true),null,20).items().get(0).capabilities()).containsExactly(RankingCapability.READ);
    }
  }
  @Test void archiveDoesNotGrantReadToMemberWithoutReadOrInactiveMembership() {
    var noRead=snapshot(member,Set.of(RankingCapability.RATE_OWN));
    var actor=new RankingActor(member,true);
    assertThatThrownBy(()->new GetRankingAccessUseCaseImpl(new RankingAuthorization((r,a)->Optional.of(noRead))).execute(actor,rank)).isInstanceOf(RankingAccessDeniedException.class);
    assertThatThrownBy(()->new ListRankingAccessUseCaseImpl((a,c,l)->List.of(noRead)).execute(actor,null,20)).isInstanceOf(RankingAccessDeniedException.class);
    var inactive=new RankingAccessSnapshot(noRead.ranking(),Optional.of(new RankingMembership(rank,owner,false,Set.of())));
    assertThatThrownBy(()->new RankingAuthorization((r,a)->Optional.of(inactive)).require(new RankingActor(owner,true),rank,RankingCapability.READ)).isInstanceOf(RankingAccessDeniedException.class);
  }
  @Test void ownerStillGetsReadWithoutExplicitGrantAndLegacyConstructorMeansNotArchived() {
    var archived=snapshot(owner,Set.of());
    assertThat(new GetRankingAccessUseCaseImpl(new RankingAuthorization((r,a)->Optional.of(archived)))
        .execute(new RankingActor(owner,true),rank).capabilities()).containsExactly(RankingCapability.READ);
    assertThat(new RankingIdentity(rank,owner,RankingVisibility.PRIVATE,RankingAdmission.INVITATION).archived()).isFalse();
  }
  @Test void archivedPortOutcomeMapsToReadOnlyConflict() {
    var useCase=new ReplaceMemberCapabilitiesUseCaseImpl((r,a,m,c)->ReplaceRankingMemberCapabilities.Outcome.ARCHIVED_READ_ONLY);
    assertThatThrownBy(()->useCase.execute(new RankingActor(owner,true),rank,member,Set.of())).isInstanceOf(RankingArchivedReadOnlyException.class);
  }
}
