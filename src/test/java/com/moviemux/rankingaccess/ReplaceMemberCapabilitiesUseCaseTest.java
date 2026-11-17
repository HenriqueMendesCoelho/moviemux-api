package com.moviemux.rankingaccess;

import static org.assertj.core.api.Assertions.*;
import com.moviemux.ranking.domain.exception.*;
import com.moviemux.ranking.domain.model.*;
import com.moviemux.ranking.domain.port.ReplaceRankingMemberCapabilities;
import com.moviemux.ranking.domain.usecase.impl.ReplaceMemberCapabilitiesUseCaseImpl;
import java.util.*;
import org.junit.jupiter.api.Test;

class ReplaceMemberCapabilitiesUseCaseTest {
  private final UUID actorId=UUID.randomUUID(),rank=UUID.randomUUID(),member=UUID.randomUUID();
  private final RankingActor actor=new RankingActor(actorId,true);
  @Test void rejectsInvalidActorAndCapabilitiesBeforePort() {
    var useCase=new ReplaceMemberCapabilitiesUseCaseImpl((r,a,m,c)->{throw new AssertionError("Must not mutate");});
    assertThatThrownBy(()->useCase.execute(null,rank,member,Set.of())).isInstanceOf(RankingAccessDeniedException.class);
    assertThatThrownBy(()->useCase.execute(new RankingActor(actorId,false),rank,member,Set.of())).isInstanceOf(RankingAccessDeniedException.class);
    assertThatThrownBy(()->useCase.execute(actor,null,member,Set.of())).isInstanceOf(RankingAccessDeniedException.class);
    assertThatThrownBy(()->useCase.execute(actor,rank,null,Set.of())).isInstanceOf(RankingAccessDeniedException.class);
    assertThatThrownBy(()->useCase.execute(actor,rank,member,null)).isInstanceOf(InvalidRankingCapabilitiesException.class);
    var invalid=new HashSet<RankingCapability>();invalid.add(null);
    assertThatThrownBy(()->useCase.execute(actor,rank,member,invalid)).isInstanceOf(InvalidRankingCapabilitiesException.class);
  }
  @Test void copiesKnownCapabilitiesAndPassesAuthenticatedActor() {
    var useCase=new ReplaceMemberCapabilitiesUseCaseImpl((r,a,m,c)->{
      assertThat(r).isEqualTo(rank);assertThat(a).isEqualTo(actorId);assertThat(m).isEqualTo(member);
      assertThat(c).containsExactly(RankingCapability.READ);
      assertThatThrownBy(()->c.clear()).isInstanceOf(UnsupportedOperationException.class);
      return ReplaceRankingMemberCapabilities.Outcome.UPDATED;
    });
    assertThatCode(()->useCase.execute(actor,rank,member,Set.of(RankingCapability.READ))).doesNotThrowAnyException();
  }
  @Test void mapsOwnerConflictAndFailsClosedForMissingOrInvalidOutcome() {
    assertThatThrownBy(()->new ReplaceMemberCapabilitiesUseCaseImpl((r,a,m,c)->null).execute(actor,rank,member,Set.of())).isInstanceOf(RankingAccessDeniedException.class);
    assertThatThrownBy(()->new ReplaceMemberCapabilitiesUseCaseImpl((r,a,m,c)->ReplaceRankingMemberCapabilities.Outcome.NOT_FOUND).execute(actor,rank,member,Set.of())).isInstanceOf(RankingAccessDeniedException.class);
    assertThatThrownBy(()->new ReplaceMemberCapabilitiesUseCaseImpl((r,a,m,c)->ReplaceRankingMemberCapabilities.Outcome.OWNER_CAPABILITIES_IMMUTABLE).execute(actor,rank,member,Set.of())).isInstanceOf(RankingOwnerCapabilitiesImmutableException.class);
  }
}
