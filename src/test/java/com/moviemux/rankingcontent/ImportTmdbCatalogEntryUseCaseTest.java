package com.moviemux.rankingcontent;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.moviemux.ranking.domain.exception.RankingAccessDeniedException;
import com.moviemux.ranking.domain.model.RankingActor;
import com.moviemux.ranking.domain.repository.CheckRankingCatalogEntryAccess;
import com.moviemux.rankingcontent.domain.exception.*;
import com.moviemux.rankingcontent.domain.model.AddedCatalogEntry;
import com.moviemux.rankingcontent.domain.model.TmdbMovieCandidate;
import com.moviemux.rankingcontent.domain.port.ImportTmdbCatalogEntry;
import com.moviemux.rankingcontent.domain.port.TmdbMovieDetailsProvider;
import com.moviemux.rankingcontent.domain.usecase.impl.ImportTmdbCatalogEntryUseCaseImpl;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ImportTmdbCatalogEntryUseCaseTest {
  private static final UUID USER=new UUID(0,1), RANK=new UUID(0,2), ENTRY=new UUID(0,3);
  private static final RankingActor ACTOR=new RankingActor(USER,true);
  private static final long ID=1, MAX=9_007_199_254_740_991L;
  private final CheckRankingCatalogEntryAccess access=mock(CheckRankingCatalogEntryAccess.class);
  private final ImportTmdbCatalogEntry writes=mock(ImportTmdbCatalogEntry.class);
  private final TmdbMovieDetailsProvider provider=mock(TmdbMovieDetailsProvider.class);
  private final ImportTmdbCatalogEntryUseCaseImpl useCase=new ImportTmdbCatalogEntryUseCaseImpl(access,writes,provider,true);

  @Test void invalidInputAndActorNeverReachAccessOrProvider() {
    for (long id:new long[]{Long.MIN_VALUE,0,MAX+1,Long.MAX_VALUE})
      assertThatThrownBy(()->useCase.execute(ACTOR,RANK,id)).isInstanceOf(InvalidTmdbIdException.class);
    for (var actor:Arrays.asList(null,new RankingActor(USER,false)))
      assertThatThrownBy(()->useCase.execute(actor,RANK,ID)).isInstanceOf(RankingEntriesDeniedException.class);
    assertThatThrownBy(()->useCase.execute(ACTOR,null,ID)).isInstanceOf(RankingEntriesDeniedException.class);
    verifyNoInteractions(access,writes,provider);
  }

  @Test void unauthorizedAndArchiveAreResolvedBeforeDisabledGateAndAnyCatalogRead() {
    var disabled=new ImportTmdbCatalogEntryUseCaseImpl(access,writes,provider,false);
    when(access.check(ACTOR,RANK)).thenThrow(new RankingAccessDeniedException()).thenReturn(true,false);
    assertThatThrownBy(()->disabled.execute(ACTOR,RANK,ID)).isInstanceOf(RankingEntriesDeniedException.class).hasNoCause();
    assertThatThrownBy(()->disabled.execute(ACTOR,RANK,ID)).isInstanceOf(ArchivedCatalogEntryException.class);
    assertThatThrownBy(()->disabled.execute(ACTOR,RANK,ID)).isInstanceOf(TmdbImportUnavailableException.class);
    verify(access,times(3)).check(ACTOR,RANK);
    verifyNoInteractions(writes,provider);
  }

  @Test void existingCanonicalOrRestoredEntryNeverFetches() {
    var reused=new AddedCatalogEntry(ENTRY,false);
    when(writes.writeExisting(RANK,USER,MAX)).thenReturn(Optional.of(reused));
    assertThat(useCase.execute(ACTOR,RANK,MAX)).isSameAs(reused);
    var order=inOrder(access,writes);
    order.verify(access).check(ACTOR,RANK);
    order.verify(writes).writeExisting(RANK,USER,MAX);
    order.verifyNoMoreInteractions();
    verifyNoInteractions(provider);
  }

  @Test void verifiedMovieWrittenAfterProviderAndNullableMetadataPreserved() {
    var candidate=movie(ID,null,"🎬".repeat(150),null,null,null,null,null,null,"linha\ntexto",LocalDate.of(2020,1,1),0);
    var created=new AddedCatalogEntry(ENTRY,true);
    when(writes.writeExisting(RANK,USER,ID)).thenReturn(Optional.empty());
    when(provider.fetch(ID)).thenReturn(candidate);
    when(writes.writeFetched(RANK,USER,candidate)).thenReturn(created);
    assertThat(useCase.execute(ACTOR,RANK,ID)).isSameAs(created);
    var order=inOrder(access,writes,provider);
    order.verify(access).check(ACTOR,RANK);
    order.verify(writes).writeExisting(RANK,USER,ID);
    order.verify(provider).fetch(ID);
    order.verify(writes).writeFetched(RANK,USER,candidate);
    order.verifyNoMoreInteractions();
  }

  @Test void expiredOrUnavailableExistingScopeNeverTouchesProvider() {
    when(writes.writeExisting(RANK,USER,ID)).thenThrow(new ExpiredEntryRecoveryException()).thenReturn(null);
    assertThatThrownBy(()->useCase.execute(ACTOR,RANK,ID)).isInstanceOf(ExpiredEntryRecoveryException.class);
    assertThatThrownBy(()->useCase.execute(ACTOR,RANK,ID)).isInstanceOf(RankingEntriesDeniedException.class);
    verify(writes,times(2)).writeExisting(RANK,USER,ID);
    verifyNoMoreInteractions(writes);
    verifyNoInteractions(provider);
  }

  @Test void invalidOrMismatchedProviderDataNeverReachesWrites() {
    when(writes.writeExisting(RANK,USER,ID)).thenReturn(Optional.empty());
    var tooLong="x".repeat(151);
    for (var malformed:Arrays.asList(null, movie(ID+1,null,null,null,null,null,null,null,null,null,null,null),
        movie(ID,"imdb123",null,null,null,null,null,null,null,null,null,null),
        movie(ID,null,tooLong,null,null,null,null,null,null,null,null,null),
        movie(ID,null,null,tooLong,null,null,null,null,null,null,null,null),
        movie(ID,null,null,null,tooLong,null,null,null,null,null,null,null),
        movie(ID,null,null,null,null,tooLong,null,null,null,null,null,null),
        movie(ID,null,null,null,null,null,null,null,null,"a"+(char)0+"b",null,null),
        movie(ID,null,null,null,null,null,"javascript:alert(1)",null,null,null,null,null),
        movie(ID,null,null,null,null,null,null,"https://example.org/{x}",null,null,null,null),
        movie(ID,null,null,null,null,null,null,null,"https://name:password@example.org/",null,null,null),
        movie(ID,null,null,null,null,null,null,null,null,null,null,-1),
        movie(ID,null,null,null,null,null,null,null,null,null,LocalDate.MAX,null),
        movie(ID,null,null,null,null,null,null,null,null,null,LocalDate.MIN,null))) {
      when(provider.fetch(ID)).thenReturn(malformed);
      assertThatThrownBy(()->useCase.execute(ACTOR,RANK,ID))
          .isInstanceOfSatisfying(TmdbProviderFailureException.class,
              ex->assertThat(ex.kind()).isEqualTo(TmdbProviderFailureException.Kind.INVALID_RESPONSE));
    }
    verify(writes,never()).writeFetched(any(),any(),any());
  }

  @Test void providerFailuresAndFetchedWriteDenialsNeverRetry() {
    when(writes.writeExisting(RANK,USER,ID)).thenReturn(Optional.empty());
    for (var failure:List.of(TmdbProviderFailureException.remoteStatus(404),
        TmdbProviderFailureException.remoteStatus(429),TmdbProviderFailureException.transport())) {
      reset(provider);
      when(provider.fetch(ID)).thenThrow(failure);
      assertThatThrownBy(()->useCase.execute(ACTOR,RANK,ID)).isSameAs(failure);
      verify(provider).fetch(ID);
      verifyNoMoreInteractions(provider);
    }
    verify(writes,never()).writeFetched(any(),any(),any());
    reset(provider,writes);
    var valid=movie(ID,"tt1234567","Filme",null,null,null,"https://example.org/poster",null,"https://example.org/video",null,null,null);
    when(provider.fetch(ID)).thenReturn(valid);
    when(writes.writeExisting(RANK,USER,ID)).thenReturn(Optional.empty());
    var conflict=new CatalogIdentityConflictException();
    when(writes.writeFetched(RANK,USER,valid)).thenThrow(conflict).thenReturn(null);
    assertThatThrownBy(()->useCase.execute(ACTOR,RANK,ID)).isSameAs(conflict);
    assertThatThrownBy(()->useCase.execute(ACTOR,RANK,ID)).isInstanceOf(RankingEntriesDeniedException.class);
    verify(provider,times(2)).fetch(ID);
    verify(writes,times(2)).writeFetched(RANK,USER,valid);
  }

  private static TmdbMovieCandidate movie(long id,String imdb,String pt,String en,String original,String director,String image,
      String ptTrailer,String enTrailer,String description,LocalDate date,Integer runtime) {
    return new TmdbMovieCandidate(id,imdb,pt,en,original,director,image,ptTrailer,enTrailer,description,date,runtime);
  }
}
