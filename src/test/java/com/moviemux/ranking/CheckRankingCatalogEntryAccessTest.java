package com.moviemux.ranking;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.moviemux.ranking.domain.exception.RankingAccessDeniedException;
import com.moviemux.ranking.domain.model.*;
import com.moviemux.ranking.domain.port.CurrentEnabledRankingAccess;
import com.moviemux.ranking.domain.usecase.impl.CheckRankingCatalogEntryAccessImpl;
import java.util.Arrays;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CheckRankingCatalogEntryAccessTest {
  private static final UUID ACTOR=new UUID(0,1), RANK=new UUID(0,2), OTHER=new UUID(0,3);
  private final RankingActor actor=new RankingActor(ACTOR,true);
  private final CurrentEnabledRankingAccess access=mock(CurrentEnabledRankingAccess.class);
  private final CheckRankingCatalogEntryAccessImpl check=new CheckRankingCatalogEntryAccessImpl(access);

  @Test void archiveStateVisibleOnlyToOtherwiseEligibleOwnerOrAddEntryMember(){
    for(boolean archived:new boolean[]{false,true}){
      for(var owner:new UUID[]{ACTOR,OTHER}){
        var grants=owner.equals(ACTOR)?Set.<RankingCapability>of():Set.of(RankingCapability.ADD_ENTRY);
        when(access.findForEnabledAccount(RANK,ACTOR)).thenReturn(Optional.of(snapshot(RANK,owner,ACTOR,true,grants,archived)));
        assertThat(check.check(actor,RANK)).isEqualTo(archived);
      }
    }
    verify(access,times(4)).findForEnabledAccount(RANK,ACTOR);
  }

  @Test void invalidActorOrMissingRankNeverGoesToStorage(){
    assertThatThrownBy(()->check.check(null,RANK)).isInstanceOf(RankingAccessDeniedException.class);
    assertThatThrownBy(()->check.check(new RankingActor(ACTOR,false),RANK)).isInstanceOf(RankingAccessDeniedException.class);
    assertThatThrownBy(()->check.check(actor,null)).isInstanceOf(RankingAccessDeniedException.class);
    verifyNoInteractions(access);
  }

  @Test void missingWrongMemberInactiveAndWrongGrantAreDeniedBeforeArchiveIsReported(){
    for(Optional<RankingAccessSnapshot> result:Arrays.asList(null,Optional.<RankingAccessSnapshot>empty(),
        Optional.of(new RankingAccessSnapshot(identity(RANK,ACTOR,true),Optional.empty())),
        Optional.of(snapshot(OTHER,ACTOR,ACTOR,true,Set.of(),true)),
        Optional.of(snapshot(RANK,ACTOR,OTHER,true,Set.of(),true)),
        Optional.of(snapshot(RANK,ACTOR,ACTOR,false,Set.of(),true)),
        Optional.of(snapshot(RANK,OTHER,ACTOR,true,Set.of(RankingCapability.READ),true)))){
      when(access.findForEnabledAccount(RANK,ACTOR)).thenReturn(result);
      assertThatThrownBy(()->check.check(actor,RANK)).isInstanceOf(RankingAccessDeniedException.class);
    }
  }

  private static RankingIdentity identity(UUID ranking,UUID owner,boolean archived){
    return new RankingIdentity(ranking,owner,RankingVisibility.PUBLIC,RankingAdmission.OPEN,archived);
  }
  private static RankingAccessSnapshot snapshot(UUID ranking,UUID owner,UUID user,boolean active,Set<RankingCapability> grants,boolean archived){
    return new RankingAccessSnapshot(identity(ranking,owner,archived),Optional.of(new RankingMembership(ranking,user,active,grants)));
  }
}
