package com.moviemux.rankingcontent;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.moviemux.ranking.domain.exception.RankingAccessDeniedException;
import com.moviemux.ranking.domain.model.RankingActor;
import com.moviemux.ranking.domain.repository.CheckRankingCatalogEntryAccess;
import com.moviemux.rankingcontent.domain.exception.*;
import com.moviemux.rankingcontent.domain.model.*;
import com.moviemux.rankingcontent.domain.port.TmdbPickerProvider;
import com.moviemux.rankingcontent.domain.usecase.impl.SearchTmdbPickerUseCaseImpl;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class SearchTmdbPickerUseCaseTest {
  private static final UUID USER=new UUID(0,1), RANK=new UUID(0,2);
  private static final RankingActor ACTOR=new RankingActor(USER,true);
  private final CheckRankingCatalogEntryAccess access=mock(CheckRankingCatalogEntryAccess.class);
  private final TmdbPickerProvider provider=mock(TmdbPickerProvider.class);
  private final AtomicBoolean enabled=new AtomicBoolean(true);
  private final SearchTmdbPickerUseCaseImpl picker=new SearchTmdbPickerUseCaseImpl(access,provider,enabled::get);

  @Test void validatesRequiredUnicodeQueryPageAndLanguageBeforeWork() {
    for(String query:Arrays.asList(null,""," é ","🎬".repeat(121),"ab"+(char)0,"ab"+(char)0xd800,"a\nb"))
      assertThatThrownBy(()->picker.search(ACTOR,RANK,query,1,"pt-BR")).isInstanceOf(InvalidTmdbSearchException.class);
    for(int page:new int[]{0,-1,21,Integer.MAX_VALUE})
      assertThatThrownBy(()->picker.search(ACTOR,RANK,"ab",page,"pt-BR")).isInstanceOf(InvalidTmdbSearchException.class);
    for(String language:Arrays.asList(null,"pt-br","en","pt-BR ","es-ES"))
      assertThatThrownBy(()->picker.search(ACTOR,RANK,"ab",1,language)).isInstanceOf(InvalidTmdbSearchException.class);
    verifyNoInteractions(access,provider);
  }

  @Test void eligibleRequestIsTrimmedWithoutInterpretingTextAsUriAndCheckedBothWays() {
    String query="🎬".repeat(100)+" {a}&page=88";
    var movie=new TmdbPickerMovie(9_007_199_254_740_991L,"Título","Original",LocalDate.of(2020,1,2),"/p_-1.jpg");
    var results=new TmdbPickerPage(List.of(movie),1,true);
    when(provider.search(query,1,"pt-BR")).thenReturn(results);
    var page=picker.search(ACTOR,RANK,"\u00a0"+query+"\u2003",1,"pt-BR");
    assertThat(page).isEqualTo(results);
    assertThatThrownBy(()->page.items().clear()).isInstanceOf(UnsupportedOperationException.class);
    var sequence=inOrder(access,provider);
    sequence.verify(access).check(ACTOR,RANK);
    sequence.verify(provider).search(query,1,"pt-BR");
    sequence.verify(access).check(ACTOR,RANK);
    sequence.verifyNoMoreInteractions();
  }

  @Test void deniedPrincipalArchiveAndDisabledGateDoNotInvokeProvider() {
    for(var actor:Arrays.asList(null,new RankingActor(USER,false))) {
      assertThatThrownBy(()->picker.search(actor,RANK,"ab",1,"en-US")).isInstanceOf(RankingEntriesDeniedException.class);
    }
    assertThatThrownBy(()->picker.search(ACTOR,null,"ab",1,"en-US")).isInstanceOf(RankingEntriesDeniedException.class);
    verifyNoInteractions(access);
    enabled.set(false);
    when(access.check(ACTOR,RANK)).thenThrow(new RankingAccessDeniedException()).thenReturn(true,false);
    assertThatThrownBy(()->picker.search(ACTOR,RANK,"ab",1,"en-US")).isInstanceOf(RankingEntriesDeniedException.class);
    assertThatThrownBy(()->picker.search(ACTOR,RANK,"ab",1,"en-US")).isInstanceOf(ArchivedCatalogEntryException.class);
    assertThatThrownBy(()->picker.search(ACTOR,RANK,"ab",1,"en-US")).isInstanceOf(TmdbImportUnavailableException.class);
    verifyNoInteractions(provider);
  }

  @Test void revocationWinsOverSuccessAndEachTypedProviderFailure() {
    for(var failure:Arrays.asList(null,TmdbProviderFailureException.remoteStatus(404),
        TmdbProviderFailureException.remoteStatus(429),TmdbProviderFailureException.invalidResponse(),TmdbProviderFailureException.transport())){
      reset(access,provider);
      when(access.check(ACTOR,RANK)).thenReturn(false).thenThrow(new RankingAccessDeniedException());
      if(failure==null) when(provider.search("ab",1,"en-US")).thenReturn(new TmdbPickerPage(List.of(),1,false));
      else when(provider.search("ab",1,"en-US")).thenThrow(failure);
      assertThatThrownBy(()->picker.search(ACTOR,RANK,"ab",1,"en-US")).isInstanceOf(RankingEntriesDeniedException.class).hasNoCause();
      verify(access,times(2)).check(ACTOR,RANK);
      verify(provider).search("ab",1,"en-US");
      verifyNoMoreInteractions(access,provider);
    }
  }

  @Test void gateAndArchiveAlsoRecheckBeforeProviderResponseAndFailures() {
    when(provider.search("ab",1,"en-US")).thenAnswer(i->{enabled.set(false);throw TmdbProviderFailureException.transport();});
    assertThatThrownBy(()->picker.search(ACTOR,RANK,"ab",1,"en-US")).isInstanceOf(TmdbImportUnavailableException.class);
    reset(access,provider);enabled.set(true);
    when(access.check(ACTOR,RANK)).thenReturn(false,true);
    when(provider.search("ab",1,"en-US")).thenReturn(new TmdbPickerPage(List.of(),1,false));
    assertThatThrownBy(()->picker.search(ACTOR,RANK,"ab",1,"en-US")).isInstanceOf(ArchivedCatalogEntryException.class);
    reset(access,provider);
    var failure=TmdbProviderFailureException.remoteStatus(404);
    when(provider.search("ab",1,"en-US")).thenThrow(failure);
    assertThatThrownBy(()->picker.search(ACTOR,RANK,"ab",1,"en-US")).isSameAs(failure);
    verify(access,times(2)).check(ACTOR,RANK);
  }

  @Test void verifiesProviderIdentityTitlePathAndPageBeforeProjection() {
    var valid=new TmdbPickerMovie(1,"Film",null,null,null);
    for(var malformed:Arrays.asList(null,new TmdbPickerPage(null,1,false),new TmdbPickerPage(List.of(valid),2,false),
        new TmdbPickerPage(Collections.nCopies(21,valid),1,false),
        page(new TmdbPickerMovie(0,"Film",null,null,null)),
        page(new TmdbPickerMovie(Long.MAX_VALUE,"Film",null,null,null)),
        page(new TmdbPickerMovie(1,null,null,null,null)),
        page(new TmdbPickerMovie(1,"\u00a0",null,null,null)),
        page(new TmdbPickerMovie(1,"🎬".repeat(251),null,null,null)),
        page(new TmdbPickerMovie(1,"Film","ab"+(char)0xdc00,null,null)),
        page(new TmdbPickerMovie(1,"Film",null,null,"//bad.jpg")),
        page(new TmdbPickerMovie(1,"Film",null,null,"/../bad.jpg")),
        page(new TmdbPickerMovie(1,"Film",null,null,"/a.jpg?x=1")),
        page(new TmdbPickerMovie(1,"Film",null,null,"/a%2Fb.jpg")),
        page(new TmdbPickerMovie(1,"Film",null,null,"/a.jpg#x")))){
      when(provider.search("ab",1,"en-US")).thenReturn(malformed);
      assertThatThrownBy(()->picker.search(ACTOR,RANK,"ab",1,"en-US"))
          .isInstanceOfSatisfying(TmdbProviderFailureException.class,
              failure->assertThat(failure.kind()).isEqualTo(TmdbProviderFailureException.Kind.INVALID_RESPONSE));
    }
  }

  @Test void posterPathsAreBoundedRelativeCdnFiles() {
    assertThat(TmdbPickerMovie.isSafePosterPath(null)).isFalse();
    assertThat(TmdbPickerMovie.isSafePosterPath("/" + "x".repeat(250) + ".jpg")).isTrue();
    assertThat(TmdbPickerMovie.isSafePosterPath("/" + "x".repeat(251) + ".jpg")).isFalse();
    assertThat(TmdbPickerMovie.isSafePosterPath("https://cdn.example/x.jpg")).isFalse();
  }

  @Test void lastPageAndEmptyResultNeverClaimMore() {
    var item=new TmdbPickerMovie(1,"Film",null,null,null);
    when(provider.search("ab",20,"en-US")).thenReturn(new TmdbPickerPage(List.of(item),20,true));
    when(provider.search("ab",1,"en-US")).thenReturn(new TmdbPickerPage(List.of(),1,true));
    assertThat(picker.search(ACTOR,RANK,"ab",20,"en-US")).isEqualTo(new TmdbPickerPage(List.of(item),20,false));
    assertThat(picker.search(ACTOR,RANK,"ab",1,"en-US")).isEqualTo(new TmdbPickerPage(List.of(),1,false));
  }

  private static TmdbPickerPage page(TmdbPickerMovie item){return new TmdbPickerPage(List.of(item),1,false);}
}
