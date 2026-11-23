package com.moviemux.rankingcontent;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.moviemux.ranking.domain.model.RankingActor;
import com.moviemux.rankingcontent.domain.exception.*;
import com.moviemux.rankingcontent.domain.model.*;
import com.moviemux.rankingcontent.domain.model.OwnRatingCopyPreviewResult.Status;
import com.moviemux.rankingcontent.domain.port.ReadOwnRatingCopyPreview;
import com.moviemux.rankingcontent.domain.port.SealOwnRatingCopyPreview;
import com.moviemux.rankingcontent.domain.usecase.impl.PreviewOwnRatingCopyUseCaseImpl;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PreviewOwnRatingCopyUseCaseTest {
  private static final UUID USER = id(1);
  private static final RankingActor ACTOR = new RankingActor(USER, true);
  private static final RatingCopyContext SOURCE = new RatingCopyContext(id(2), id(3), id(4));
  private static final RatingCopyContext DEST = new RatingCopyContext(id(5), id(6), id(7));
  private static final RatingCopyPair PAIR = new RatingCopyPair(SOURCE, DEST);
  private static final Instant CREATED = Instant.parse("2026-11-01T12:00:00Z"), EXPIRY = CREATED.plusSeconds(60);
  private final ReadOwnRatingCopyPreview reader = mock(ReadOwnRatingCopyPreview.class);
  private final SealOwnRatingCopyPreview sealer = mock(SealOwnRatingCopyPreview.class);
  private final PreviewOwnRatingCopyUseCaseImpl useCase = new PreviewOwnRatingCopyUseCaseImpl(reader, sealer);

  @Test void invalidActorAndSameRankNeverReadOrProbeConfiguration() {
    for (var actor : Arrays.asList(null, new RankingActor(USER, false)))
      assertThatThrownBy(() -> useCase.preview(actor, PAIR)).isInstanceOf(RankingEntriesDeniedException.class);
    assertThatThrownBy(() -> useCase.preview(ACTOR, new RatingCopyPair(SOURCE, SOURCE)))
        .isInstanceOf(RankingEntriesDeniedException.class);
    verifyNoInteractions(reader, sealer);
  }

  @Test void incompleteCompoundScopeIsBadRequestBeforeAnythingElse() {
    for (var pair : Arrays.asList(null, new RatingCopyPair(null, DEST), new RatingCopyPair(SOURCE, null),
        new RatingCopyPair(new RatingCopyContext(null,id(3),id(4)),DEST),
        new RatingCopyPair(SOURCE,new RatingCopyContext(id(5),null,id(7))),
        new RatingCopyPair(new RatingCopyContext(id(2),id(3),null),DEST)))
      assertThatThrownBy(() -> useCase.preview(ACTOR, pair)).isInstanceOf(InvalidRatingCopyException.class);
    verifyNoInteractions(reader, sealer);
  }

  @Test void absentOrDeniedScopeNeverProbesConfiguration() {
    when(reader.find(USER, PAIR)).thenReturn(null, Optional.empty());
    for (int i=0; i<2; i++)
      assertThatThrownBy(() -> useCase.preview(ACTOR, PAIR)).isInstanceOf(RankingEntriesDeniedException.class);
    verifyNoInteractions(sealer);
  }

  @Test void configuredIsRequiredEvenOnBlockedReadinessAndDoesNotSeal() {
    doThrow(new RatingCopyUnavailableException()).when(sealer).requireConfigured();
    for (boolean destinationExists : List.of(false,true)) {
      when(reader.find(USER, PAIR)).thenReturn(Optional.of(new RatingCopyPreviewSnapshot(null,destinationExists)));
      assertThatThrownBy(() -> useCase.preview(ACTOR, PAIR)).isInstanceOf(RatingCopyUnavailableException.class);
    }
    var sequence = inOrder(reader,sealer);
    sequence.verify(reader).find(USER,PAIR); sequence.verify(sealer).requireConfigured();
    sequence.verify(reader).find(USER,PAIR); sequence.verify(sealer).requireConfigured();
    verifyNoMoreInteractions(reader,sealer);
  }

  @Test void blockedStatusesRevealNeitherScoreTokenNorExpiry() {
    for (var source : Arrays.asList(null, version(null),version(-1),version(11)))
      assertThat(preview(source,false)).isEqualTo(new OwnRatingCopyPreviewResult(Status.SOURCE_INELIGIBLE,null,null,null));
    for (var source : Arrays.asList(null,version(null),version(8)))
      assertThat(preview(source,true)).isEqualTo(new OwnRatingCopyPreviewResult(Status.DESTINATION_EXISTS,null,null,null));
    verify(sealer,times(7)).requireConfigured();
    verifyNoMoreInteractions(sealer);
  }

  @Test void readySealsAuthoritativeBoundariesAndRedactsDiagnosticStrings() {
    var sequence=inOrder(reader,sealer);
    for (int score : new int[]{0,10}) {
      var source=new RatingCopySourceVersion(score,CREATED,CREATED.plusSeconds(score));
      var issued=new IssuedRatingCopyPreview("synthetic-sensitive",EXPIRY);
      when(sealer.seal(USER,PAIR,source)).thenReturn(issued);
      var result=preview(source,false);
      assertThat(result).isEqualTo(new OwnRatingCopyPreviewResult(Status.READY,score,issued.token(),EXPIRY));
      assertThat(result.toString()).doesNotContain(issued.token());
      assertThat(issued.toString()).doesNotContain(issued.token());
      assertThat(source.toString()).doesNotContain("score","createdAt","updatedAt");
      sequence.verify(reader).find(USER,PAIR); sequence.verify(sealer).requireConfigured();
      sequence.verify(sealer).seal(USER,PAIR,source);
    }
    sequence.verifyNoMoreInteractions();
  }

  @Test void incompleteFingerprintOrSealerResponseFailsClosed() {
    when(reader.find(USER, PAIR)).thenReturn(Optional.of(new RatingCopyPreviewSnapshot(
        new RatingCopySourceVersion(8,null,null),false)));
    assertThatThrownBy(() -> useCase.preview(ACTOR, PAIR)).isInstanceOf(RatingCopyUnavailableException.class);
    var source=version(8);
    when(reader.find(USER, PAIR)).thenReturn(Optional.of(new RatingCopyPreviewSnapshot(source,false)));
    for (var issued : Arrays.asList(null,new IssuedRatingCopyPreview(null,EXPIRY),
        new IssuedRatingCopyPreview(" ",EXPIRY),new IssuedRatingCopyPreview("synthetic",null))) {
      when(sealer.seal(USER,PAIR,source)).thenReturn(issued);
      assertThatThrownBy(() -> useCase.preview(ACTOR, PAIR)).isInstanceOf(RatingCopyUnavailableException.class).hasNoCause();
    }
    verify(sealer,times(4)).seal(USER,PAIR,source);
  }

  private OwnRatingCopyPreviewResult preview(RatingCopySourceVersion source, boolean destinationExists) {
    when(reader.find(USER, PAIR)).thenReturn(Optional.of(new RatingCopyPreviewSnapshot(source,destinationExists)));
    return useCase.preview(ACTOR, PAIR);
  }

  private static RatingCopySourceVersion version(Integer score) { return new RatingCopySourceVersion(score,CREATED,null); }
  private static UUID id(long n) { return new UUID(0,n); }
}
