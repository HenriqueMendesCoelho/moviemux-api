package com.moviemux.rankingcontent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import com.moviemux.ranking.domain.model.RankingActor;
import com.moviemux.rankingcontent.domain.exception.InvalidRatingCopyException;
import com.moviemux.rankingcontent.domain.exception.RankingEntriesDeniedException;
import com.moviemux.rankingcontent.domain.model.BulkOwnRatingCopyPreview.*;
import com.moviemux.rankingcontent.domain.port.ReadOwnRatingsBulkPreview;
import com.moviemux.rankingcontent.domain.usecase.impl.PreviewOwnRatingsBulkUseCaseImpl;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PreviewOwnRatingsBulkUseCaseTest {
  private final UUID source=new UUID(0,1),target=new UUID(0,2);
  private final RankingActor actor=new RankingActor(new UUID(0,3),true);
  private final Pairing pair=new Pairing(new UUID(0,4),new UUID(0,5),new UUID(0,6),new UUID(0,7));
  private final ReadOwnRatingsBulkPreview reader=mock(ReadOwnRatingsBulkPreview.class);
  private final PreviewOwnRatingsBulkUseCaseImpl useCase=new PreviewOwnRatingsBulkUseCaseImpl(reader);

  @Test void preservesOrderAndMatchesSinglePreviewPrecedenceButNeverReturnsTokens() {
    var batch=new Batch(source,target,Collections.nCopies(7,pair));
    when(reader.find(actor.userId(),batch)).thenReturn(List.of(
        new Candidate(0,false,true,7),new Candidate(1,true,true,null),new Candidate(2,true,false,null),
        new Candidate(3,true,false,-1),new Candidate(4,true,false,11),new Candidate(5,true,false,0),
        new Candidate(6,true,false,10)));
    assertThat(useCase.preview(actor,batch)).containsExactly(
        new Result(0,Status.UNAVAILABLE,null),new Result(1,Status.DESTINATION_EXISTS,null),
        new Result(2,Status.SOURCE_INELIGIBLE,null),new Result(3,Status.SOURCE_INELIGIBLE,null),
        new Result(4,Status.SOURCE_INELIGIBLE,null),new Result(5,Status.READY,0),new Result(6,Status.READY,10));
    verify(reader).find(actor.userId(),batch);verifyNoMoreInteractions(reader);
  }

  @Test void wrongShapeAndInactivePrincipalDoNotEnterRepository() {
    var valid=new Batch(source,target,List.of(pair));
    assertThatThrownBy(()->useCase.preview(null,valid)).isInstanceOf(RankingEntriesDeniedException.class);
    assertThatThrownBy(()->useCase.preview(new RankingActor(actor.userId(),false),valid))
        .isInstanceOf(RankingEntriesDeniedException.class);
    for(var bad:Arrays.asList(null,new Batch(null,target,List.of(pair)),new Batch(source,null,List.of(pair)),
        new Batch(source,source,List.of(pair)),new Batch(source,target,null),
        new Batch(source,target,List.of()),new Batch(source,target,Collections.nCopies(51,pair)),
        new Batch(source,target,Collections.singletonList(null)),
        new Batch(source,target,List.of(new Pairing(null,pair.sourceRoundId(),pair.destinationEntryId(),pair.destinationRoundId()))),
        new Batch(source,target,List.of(new Pairing(pair.sourceEntryId(),null,pair.destinationEntryId(),pair.destinationRoundId()))),
        new Batch(source,target,List.of(new Pairing(pair.sourceEntryId(),pair.sourceRoundId(),null,pair.destinationRoundId()))),
        new Batch(source,target,List.of(new Pairing(pair.sourceEntryId(),pair.sourceRoundId(),pair.destinationEntryId(),null))))) {
      assertThatThrownBy(()->useCase.preview(actor,bad)).isInstanceOf(InvalidRatingCopyException.class);
    }
    verifyNoInteractions(reader);
  }

  @Test void rejectsInconsistentSnapshotBeforeReturningAnyData() {
    var batch=new Batch(source,target,List.of(pair));
    for(var data:Arrays.<List<Candidate>>asList(null,List.of(),Collections.singletonList(null),
        List.of(new Candidate(1,true,false,8)))) {
      reset(reader);when(reader.find(actor.userId(),batch)).thenReturn(data);
      assertThatThrownBy(()->useCase.preview(actor,batch)).isInstanceOf(RankingEntriesDeniedException.class);
    }
  }
}
