package com.moviemux.rankingcontent;

import static org.assertj.core.api.Assertions.*;
import com.moviemux.ranking.domain.model.RankingActor;
import com.moviemux.rankingcontent.domain.exception.*;
import com.moviemux.rankingcontent.domain.model.AddedCatalogEntry;
import com.moviemux.rankingcontent.domain.usecase.impl.AddExistingCatalogEntryUseCaseImpl;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class AddExistingCatalogEntryUseCaseTest {
  private final UUID rank=UUID.randomUUID(),user=UUID.randomUUID(),movie=UUID.randomUUID(),entry=UUID.randomUUID();
  private final RankingActor actor=new RankingActor(user,true);
  @Test void invalidActorScopeAndMovieFailBeforeWriting() {
    var useCase=new AddExistingCatalogEntryUseCaseImpl((r,a,m)->{throw new AssertionError("Invalid input must not write");});
    assertThatThrownBy(()->useCase.execute(null,rank,movie)).isInstanceOf(RankingEntriesDeniedException.class);
    assertThatThrownBy(()->useCase.execute(new RankingActor(user,false),rank,movie)).isInstanceOf(RankingEntriesDeniedException.class);
    assertThatThrownBy(()->useCase.execute(actor,null,movie)).isInstanceOf(RankingEntriesDeniedException.class);
    assertThatThrownBy(()->useCase.execute(actor,rank,null)).isInstanceOf(InvalidCatalogEntryException.class);
  }
  @Test void forwardsOnlyAuthenticatedActorAndIdentifiersPreservingCreatedAndExistingResults() {
    for(boolean created:new boolean[]{true,false}) {
      var result=new AddedCatalogEntry(entry,created);var writes=new AtomicInteger();
      var useCase=new AddExistingCatalogEntryUseCaseImpl((r,a,m)->{
        assertThat(r).isEqualTo(rank);assertThat(a).isEqualTo(user);assertThat(m).isEqualTo(movie);writes.incrementAndGet();return result;
      });
      assertThat(useCase.execute(actor,rank,movie)).isSameAs(result);assertThat(writes.get()).isEqualTo(1);
    }
  }
  @Test void nullResultFailsClosedAndDomainDenialsAreNotConvertedIntoSuccess() {
    assertThatThrownBy(()->new AddExistingCatalogEntryUseCaseImpl((r,a,m)->null).execute(actor,rank,movie)).isInstanceOf(RankingEntriesDeniedException.class);
    assertThatThrownBy(()->new AddExistingCatalogEntryUseCaseImpl((r,a,m)->{throw new ArchivedCatalogEntryException();}).execute(actor,rank,movie)).isInstanceOf(ArchivedCatalogEntryException.class);
    assertThatThrownBy(()->new AddExistingCatalogEntryUseCaseImpl((r,a,m)->{throw new RankingEntriesDeniedException();}).execute(actor,rank,movie)).isInstanceOf(RankingEntriesDeniedException.class);
    assertThatThrownBy(()->new AddExistingCatalogEntryUseCaseImpl((r,a,m)->{throw new InvalidCatalogEntryException();}).execute(actor,rank,movie)).isInstanceOf(InvalidCatalogEntryException.class);
  }
}
