package com.moviemux.ranking;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import com.moviemux.ranking.domain.model.*;
import com.moviemux.ranking.domain.port.*;
import com.moviemux.ranking.domain.exception.*;
import com.moviemux.ranking.domain.usecase.impl.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class RankingCreationAndSummaryUseCaseTest {
  private final UUID user=new UUID(0,1),rank=new UUID(0,2);
  private final RankingActor actor=new RankingActor(user,true);
  private final CreateRanking writes=mock(CreateRanking.class);
  private final CurrentRankingCreationQuota quotas=mock(CurrentRankingCreationQuota.class);
  private final ListRankingSummaries reads=mock(ListRankingSummaries.class);
  private final CreateRankingUseCaseImpl create=new CreateRankingUseCaseImpl(writes,quotas);
  private final ListRankingSummariesUseCaseImpl list=new ListRankingSummariesUseCaseImpl(reads);
  private RankingSummary summary(UUID id,String name){
    return new RankingSummary(id,name,RankingVisibility.PRIVATE,RankingAdmission.INVITATION,EnumSet.allOf(RankingCapability.class));
  }
  private RankingSummary create(String name){
    return create.create(actor,name,RankingVisibility.PRIVATE,RankingAdmission.INVITATION);
  }
  @Test void invalidActorsNeverReadOrWriteOrConsultQuota(){
    for(RankingActor invalid:Arrays.asList(null,new RankingActor(user,false))){
      assertThatThrownBy(()->create.create(invalid,"Name",RankingVisibility.PRIVATE,RankingAdmission.OPEN)).isInstanceOf(RankingAccessDeniedException.class);
      assertThatThrownBy(()->list.list(invalid,null,20)).isInstanceOf(RankingAccessDeniedException.class);
    }
    verifyNoInteractions(writes,reads,quotas);
  }
  @Test void trimsUnicodeAndCountsCodepointsInsteadOfUtf16Units(){
    var quota=new RankingCreationQuota(4);
    when(quotas.find()).thenReturn(Optional.of(quota));
    String name="🎬".repeat(120);
    var expected=summary(rank,name);
    when(writes.create(user,name,RankingVisibility.PRIVATE,RankingAdmission.INVITATION,quota)).thenReturn(expected);
    assertThat(create("\u00a0 "+name+"\u2003")).isSameAs(expected);
    verify(writes).create(user,name,RankingVisibility.PRIVATE,RankingAdmission.INVITATION,quota);
    verifyNoMoreInteractions(writes);
  }
  @Test void invalidNamesAndEnumsAreRejectedBeforeQuotaOrMutation(){
    for(String invalid:Arrays.asList(null,""," ","\u00a0\u2003","a".repeat(121),"🎬".repeat(121),
        "a\u0000b","a\nb","\tname","a\u007fb","\ud800","\udc00","\ud800x")){
      assertThatThrownBy(()->create(invalid)).isInstanceOf(InvalidRankingCreationException.class);
    }
    assertThatThrownBy(()->create.create(actor,"Name",null,RankingAdmission.OPEN)).isInstanceOf(InvalidRankingCreationException.class);
    assertThatThrownBy(()->create.create(actor,"Name",RankingVisibility.PRIVATE,null)).isInstanceOf(InvalidRankingCreationException.class);
    verifyNoInteractions(writes,reads,quotas);
  }
  @Test void absentAndInvalidQuotaNeverChooseDefault(){
    when(quotas.find()).thenReturn(null);
    assertThatThrownBy(()->create("Name")).isInstanceOf(RankingCreationUnavailableException.class);
    when(quotas.find()).thenReturn(Optional.empty());
    assertThatThrownBy(()->create("Name")).isInstanceOf(RankingCreationUnavailableException.class);
    for(int max:new int[]{0,-1}){
      when(quotas.find()).thenReturn(Optional.of(new RankingCreationQuota(max)));
      assertThatThrownBy(()->create("Name")).isInstanceOf(RankingCreationUnavailableException.class);
    }
    verifyNoInteractions(writes);
  }
  @Test void explicitCapsPassUnchangedAndLimitFailureNeverRetries(){
    for(int max:List.of(3,25)){
      reset(writes);
      var quota=new RankingCreationQuota(max);
      when(quotas.find()).thenReturn(Optional.of(quota));
      var failure=new RankingCreationLimitException();
      when(writes.create(user,"Name",RankingVisibility.PRIVATE,RankingAdmission.INVITATION,quota)).thenThrow(failure);
      assertThatThrownBy(()->create("Name")).isSameAs(failure);
      verify(writes).create(user,"Name",RankingVisibility.PRIVATE,RankingAdmission.INVITATION,quota);
      verifyNoMoreInteractions(writes);
    }
  }
  @Test void malformedCreationResultsFailClosed(){
    when(quotas.find()).thenReturn(Optional.of(new RankingCreationQuota(3)));
    for(RankingSummary result:Arrays.asList(null,summary(null,"Name"),summary(rank,null),summary(rank,"Other"),
        new RankingSummary(rank,"Name",null,RankingAdmission.INVITATION,Set.of()),
        new RankingSummary(rank,"Name",RankingVisibility.PRIVATE,null,Set.of()),
        new RankingSummary(rank,"Name",RankingVisibility.PRIVATE,RankingAdmission.INVITATION,null),
        new RankingSummary(rank,"Name",RankingVisibility.PRIVATE,RankingAdmission.INVITATION,Set.of(RankingCapability.READ)))){
      when(writes.create(any(),any(),any(),any(),any())).thenReturn(result);
      assertThatThrownBy(()->create("Name")).isInstanceOf(RankingAccessDeniedException.class);
    }
  }
  @Test void paginationUsesUnsignedUuidOrderAndPreservesNullLegacyName(){
    UUID first=UUID.fromString("7fffffff-ffff-ffff-ffff-ffffffffffff");
    UUID next=UUID.fromString("80000000-0000-0000-0000-000000000000");
    var unnamed=summary(first,null);
    when(reads.find(user,null,2)).thenReturn(List.of(unnamed,summary(next,"Second")));
    var page=list.list(actor,null,1);
    assertThat(page.items()).containsExactly(unnamed);
    assertThat(page.nextCursor()).isEqualTo(first);
    when(reads.find(user,first,2)).thenReturn(List.of(summary(next,"Second")));
    assertThat(list.list(actor,first,1).nextCursor()).isNull();
    when(reads.find(user,next,2)).thenReturn(List.of());
    assertThat(list.list(actor,next,1).items()).isEmpty();
    assertThatThrownBy(()->page.items().clear()).isInstanceOf(UnsupportedOperationException.class);
  }
  @Test void invalidLimitsNeverRead(){
    for(int limit:new int[]{-1,0,101,Integer.MAX_VALUE})
      assertThatThrownBy(()->list.list(actor,null,limit)).isInstanceOf(InvalidRankingAccessPageException.class);
    verifyNoInteractions(reads);
  }
  @Test void malformedRowsAndLookaheadAreRejectedBeforeReturningPage(){
    when(reads.find(user,null,2)).thenReturn(null);
    assertThatThrownBy(()->list.list(actor,null,1)).isInstanceOf(RankingAccessDeniedException.class);
    for(List<RankingSummary> result:List.of(
        List.of(summary(rank,"a"),summary(new UUID(0,3),"b"),summary(new UUID(0,4),"c")),
        Arrays.asList(summary(rank,"a"),null),
        List.of(summary(null,"a")),
        List.of(new RankingSummary(rank,null,null,RankingAdmission.OPEN,Set.of(RankingCapability.READ))),
        List.of(new RankingSummary(rank,null,RankingVisibility.PUBLIC,null,Set.of(RankingCapability.READ))),
        List.of(new RankingSummary(rank,null,RankingVisibility.PUBLIC,RankingAdmission.OPEN,null)),
        List.of(new RankingSummary(rank,null,RankingVisibility.PUBLIC,RankingAdmission.OPEN,Set.of())),
        List.of(summary(rank,"a"),summary(rank,"duplicate")),
        List.of(summary(new UUID(0,3),"a"),summary(rank,"backward")))){
      when(reads.find(user,null,2)).thenReturn(result);
      assertThatThrownBy(()->list.list(actor,null,1)).isInstanceOf(RankingAccessDeniedException.class);
    }
    when(reads.find(user,rank,2)).thenReturn(List.of(summary(rank,"cursor")));
    assertThatThrownBy(()->list.list(actor,rank,1)).isInstanceOf(RankingAccessDeniedException.class);
  }
}
