package com.moviemux.rankingcontent;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.moviemux.ranking.domain.model.RankingActor;
import com.moviemux.rankingcontent.domain.exception.*;
import com.moviemux.rankingcontent.domain.model.*;
import com.moviemux.rankingcontent.domain.port.*;
import com.moviemux.rankingcontent.domain.usecase.impl.ApplyOwnRatingCopyUseCaseImpl;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ApplyOwnRatingCopyUseCaseTest {
  private static final UUID USER=id(1), OP=id(8);
  private static final RankingActor ACTOR=new RankingActor(USER,true);
  private static final RatingCopyContext SOURCE=new RatingCopyContext(id(2),id(3),id(4));
  private static final RatingCopyContext TARGET=new RatingCopyContext(id(5),id(6),id(7));
  private static final RatingCopyPair PAIR=new RatingCopyPair(SOURCE,TARGET);
  private static final Instant EXPIRY=Instant.parse("2026-11-01T00:00:00Z");
  private static final RatingCopySourceVersion SOURCE_VERSION=new RatingCopySourceVersion(0,EXPIRY.minusSeconds(60),null);
  private static final String TOKEN="A".repeat(263)+"_";
  private final OpenOwnRatingCopyPreview opener=mock(OpenOwnRatingCopyPreview.class);
  private final ApplyOwnRatingCopy writes=mock(ApplyOwnRatingCopy.class);
  private final CopyApplyAvailability gate=mock(CopyApplyAvailability.class);
  private final ApplyOwnRatingCopyUseCaseImpl useCase=new ApplyOwnRatingCopyUseCaseImpl(opener,writes,gate);

  @Test void actorAndCanonicalGrammarFailBeforeCryptoOrStorage() {
    for(var actor:Arrays.asList(null,new RankingActor(USER,false)))
      assertThatThrownBy(()->useCase.apply(actor,TARGET,TOKEN)).isInstanceOf(RankingEntriesDeniedException.class);
    for(String token:Arrays.asList(null,"","A".repeat(263),TOKEN+"A","A".repeat(263)+"=","A".repeat(263)+"+"," "+TOKEN))
      assertThatThrownBy(()->useCase.apply(ACTOR,TARGET,token)).isInstanceOf(InvalidRatingCopyException.class);
    for(var context:Arrays.asList(null,new RatingCopyContext(null,id(6),id(7)),
        new RatingCopyContext(id(5),null,id(7)),new RatingCopyContext(id(5),id(6),null)))
      assertThatThrownBy(()->useCase.apply(ACTOR,context,TOKEN)).isInstanceOf(InvalidRatingCopyException.class);
    verifyNoInteractions(opener,writes,gate);
  }

  @Test void failedAuthenticationDoesNotProbeTargetOrGate() {
    var invalid=new InvalidRatingCopyException();
    when(opener.open(TOKEN)).thenThrow(invalid);
    assertThatThrownBy(()->useCase.apply(ACTOR,TARGET,TOKEN)).isSameAs(invalid);
    verifyNoInteractions(writes,gate);
  }

  @Test void unavailableCryptoOnlyReturns503AfterIndependentTargetAuthorization() {
    when(opener.open(TOKEN)).thenThrow(new RatingCopyUnavailableException());
    doNothing().doThrow(new RankingEntriesDeniedException()).when(writes).requireTarget(USER,TARGET);
    assertThatThrownBy(()->useCase.apply(ACTOR,TARGET,TOKEN)).isInstanceOf(RatingCopyUnavailableException.class).hasNoCause();
    assertThatThrownBy(()->useCase.apply(ACTOR,TARGET,TOKEN)).isInstanceOf(RankingEntriesDeniedException.class);
    verify(writes,times(2)).requireTarget(USER,TARGET);verifyNoMoreInteractions(writes);verifyNoInteractions(gate);
  }

  @Test void crossActorCrossPathAndSameRankNeverProbeScopeOrGate() {
    for(var opened:List.of(
        new OpenedRatingCopyPreview(OP,id(99),PAIR,EXPIRY,SOURCE_VERSION),
        new OpenedRatingCopyPreview(OP,USER,new RatingCopyPair(SOURCE,new RatingCopyContext(id(5),id(6),id(99))),EXPIRY,SOURCE_VERSION),
        new OpenedRatingCopyPreview(OP,USER,new RatingCopyPair(SOURCE,new RatingCopyContext(id(5),id(99),id(7))),EXPIRY,SOURCE_VERSION),
        new OpenedRatingCopyPreview(OP,USER,new RatingCopyPair(SOURCE,new RatingCopyContext(id(99),id(6),id(7))),EXPIRY,SOURCE_VERSION),
        new OpenedRatingCopyPreview(OP,USER,new RatingCopyPair(TARGET,TARGET),EXPIRY,SOURCE_VERSION))) {
      when(opener.open(TOKEN)).thenReturn(opened);
      assertThatThrownBy(()->useCase.apply(ACTOR,TARGET,TOKEN)).isInstanceOf(RankingEntriesDeniedException.class);
    }
    verifyNoInteractions(writes,gate);
  }

  @Test void malformedAuthenticatedMaterialFailsClosedBeforeStorage() {
    for(var opened:Arrays.asList(null,new OpenedRatingCopyPreview(OP,null,PAIR,EXPIRY,SOURCE_VERSION),
        new OpenedRatingCopyPreview(OP,USER,null,EXPIRY,SOURCE_VERSION),
        new OpenedRatingCopyPreview(OP,USER,new RatingCopyPair(SOURCE,null),EXPIRY,SOURCE_VERSION),
        new OpenedRatingCopyPreview(OP,USER,new RatingCopyPair(null,TARGET),EXPIRY,SOURCE_VERSION),
        new OpenedRatingCopyPreview(null,USER,PAIR,EXPIRY,SOURCE_VERSION),
        new OpenedRatingCopyPreview(OP,USER,PAIR,null,SOURCE_VERSION),
        new OpenedRatingCopyPreview(OP,USER,PAIR,EXPIRY,null),
        opened(new RatingCopySourceVersion(0,null,null)),opened(new RatingCopySourceVersion(null,EXPIRY,null)),
        opened(new RatingCopySourceVersion(-1,EXPIRY,null)),opened(new RatingCopySourceVersion(11,EXPIRY,null)))) {
      when(opener.open(TOKEN)).thenReturn(opened);
      assertThatThrownBy(()->useCase.apply(ACTOR,TARGET,TOKEN)).isInstanceOf(InvalidRatingCopyException.class);
    }
    verifyNoInteractions(writes,gate);
  }

  @Test void opaqueCommandAndGateAreDelegatedWithoutAppClockOrReplayMutation() {
    var opened=opened(SOURCE_VERSION);
    when(opener.open(TOKEN)).thenReturn(opened);
    when(gate.available()).thenReturn(false,true,true);
    when(writes.apply(USER,opened,false)).thenThrow(new RatingCopyUnavailableException());
    var rating=new OwnRoundRating(TARGET.roundId(),0);
    when(writes.apply(USER,opened,true)).thenReturn(rating);
    assertThatThrownBy(()->useCase.apply(ACTOR,TARGET,TOKEN)).isInstanceOf(RatingCopyUnavailableException.class);
    assertThat(useCase.apply(ACTOR,TARGET,TOKEN)).isSameAs(rating);
    assertThat(useCase.apply(ACTOR,TARGET,TOKEN)).isSameAs(rating);
    assertThat(opened.toString()).isEqualTo("OpenedRatingCopyPreview[redacted]");
    var sequence=inOrder(opener,gate,writes);
    sequence.verify(opener).open(TOKEN);sequence.verify(gate).available();sequence.verify(writes).apply(USER,opened,false);
    for(int i=0;i<2;i++){
      sequence.verify(opener).open(TOKEN);sequence.verify(gate).available();sequence.verify(writes).apply(USER,opened,true);
    }
    sequence.verifyNoMoreInteractions();
  }

  @Test void unexpectedResultsAndDomainDenialsCauseNoSecondWrite() {
    var opened=opened(SOURCE_VERSION);
    when(opener.open(TOKEN)).thenReturn(opened);
    when(gate.available()).thenReturn(true);
    when(writes.apply(USER,opened,true)).thenReturn(null,new OwnRoundRating(id(99),0),new OwnRoundRating(TARGET.roundId(),1))
        .thenThrow(new StaleRatingCopyException(),new DuplicateOwnRoundRatingException(),new RankingEntriesDeniedException());
    for(int i=0;i<3;i++)
      assertThatThrownBy(()->useCase.apply(ACTOR,TARGET,TOKEN)).isInstanceOf(RankingEntriesDeniedException.class);
    assertThatThrownBy(()->useCase.apply(ACTOR,TARGET,TOKEN)).isInstanceOf(StaleRatingCopyException.class);
    assertThatThrownBy(()->useCase.apply(ACTOR,TARGET,TOKEN)).isInstanceOf(DuplicateOwnRoundRatingException.class);
    assertThatThrownBy(()->useCase.apply(ACTOR,TARGET,TOKEN)).isInstanceOf(RankingEntriesDeniedException.class);
    verify(writes,times(6)).apply(USER,opened,true);verifyNoMoreInteractions(writes);
  }

  private static OpenedRatingCopyPreview opened(RatingCopySourceVersion version){
    return new OpenedRatingCopyPreview(OP,USER,PAIR,EXPIRY,version);
  }
  private static UUID id(long n){return new UUID(0,n);}
}
