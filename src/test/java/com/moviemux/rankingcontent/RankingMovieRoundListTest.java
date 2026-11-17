package com.moviemux.rankingcontent;
import static org.assertj.core.api.Assertions.*;
import com.moviemux.ranking.domain.exception.*;
import com.moviemux.ranking.domain.model.RankingActor;
import com.moviemux.rankingcontent.domain.model.*;
import com.moviemux.rankingcontent.domain.usecase.RankingMovieRoundListUseCase;
import com.moviemux.rankingcontent.adapter.controller.RankingMovieRoundListController;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
class RankingMovieRoundListTest {
  private final UUID actor=UUID.randomUUID(),rank=UUID.randomUUID(),entry=UUID.randomUUID();
  private RankingMovieRoundListItem row(long n,Boolean revealed) {
    return new RankingMovieRoundListItem(UUID.randomUUID(),n,null,Instant.EPOCH,null,revealed);
  }
  @Test void keysetLookaheadDoesNotNormalizeNullableRevealOrExposeExtraRow() {
    var first=row(2,null);var second=row(8,false);var third=row(12,true);
    var useCase=new RankingMovieRoundListUseCase((a,r,e,after,max) -> {
      assertThat(a).isEqualTo(actor);assertThat(r).isEqualTo(rank);assertThat(e).isEqualTo(entry);
      assertThat(after).isEqualTo(1L);assertThat(max).isEqualTo(3);
      return Optional.of(List.of(first,second,third));
    });
    var page=useCase.execute(new RankingActor(actor,true),rank,entry,1L,2);
    assertThat(page.items()).containsExactly(first,second);assertThat(page.nextCursor()).isEqualTo(8L);
    assertThat(page.items().getFirst().revealed()).isNull();
  }
  @Test void authorizedEmptyPageAndInaccessibleHaveDistinctInternalResults() {
    var empty=new RankingMovieRoundListUseCase((a,r,e,n,l)->Optional.of(List.of()));
    assertThat(empty.execute(new RankingActor(actor,true),rank,entry,null,20).items()).isEmpty();
    assertThat(empty.execute(new RankingActor(actor,true),rank,entry,Long.MAX_VALUE,20).nextCursor()).isNull();
    var denied=new RankingMovieRoundListUseCase((a,r,e,n,l)->Optional.empty());
    assertThatThrownBy(()->denied.execute(new RankingActor(actor,true),rank,entry,null,20)).isInstanceOf(RankingAccessDeniedException.class);
  }
  @Test void invalidPaginationAndDisabledActorNeverQueryStorage() {
    var useCase=new RankingMovieRoundListUseCase((a,r,e,n,l)->{throw new AssertionError("storage must not run");});
    for(int limit:new int[]{0,101}) assertThatThrownBy(()->useCase.execute(new RankingActor(actor,true),rank,entry,null,limit)).isInstanceOf(InvalidRankingAccessPageException.class);
    for(long after:new long[]{0,-1}) assertThatThrownBy(()->useCase.execute(new RankingActor(actor,true),rank,entry,after,20)).isInstanceOf(InvalidRankingAccessPageException.class);
    assertThatThrownBy(()->useCase.execute(new RankingActor(actor,false),rank,entry,null,20)).isInstanceOf(RankingAccessDeniedException.class);
  }
  @Test void invalidOrderingIncludingLookaheadFailsClosed() {
    var useCase=new RankingMovieRoundListUseCase((a,r,e,n,l)->Optional.of(List.of(row(2,null),row(1,true))));
    assertThatThrownBy(()->useCase.execute(new RankingActor(actor,true),rank,entry,null,1)).isInstanceOf(RankingAccessDeniedException.class);
  }
  @Test void serializedProjectionIsAllowlistedAndPreservesExplicitNulls() throws Exception {
    var mapper=new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules();
    mapper.setSerializationInclusion(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL);
    var item=new RankingMovieRoundListController.RoundResponse(UUID.randomUUID(),1,null,Instant.EPOCH,null,null);
    var json=mapper.valueToTree(new RankingMovieRoundListController.PageResponse(List.of(item),null));
    var fields=new HashSet<String>();json.get("items").get(0).fieldNames().forEachRemaining(fields::add);
    assertThat(fields).containsExactlyInAnyOrder("roundId","number","description","createdAt","updatedAt","revealed");
    assertThat(json.get("items").get(0).get("revealed").isNull()).isTrue();assertThat(json.get("nextCursor").isNull()).isTrue();
  }
  @Test void nullPortResultFailsClosed() {
    var useCase=new RankingMovieRoundListUseCase((a,r,e,n,l)->null);
    assertThatThrownBy(()->useCase.execute(new RankingActor(actor,true),rank,entry,null,20))
        .isInstanceOf(RankingAccessDeniedException.class);
  }

  @Test void missingActorOrScopeNeverQueriesStorage() {
    var useCase=new RankingMovieRoundListUseCase((a,r,e,n,l)->{throw new AssertionError("storage must not run");});
    assertThatThrownBy(()->useCase.execute(null,rank,entry,null,20)).isInstanceOf(RankingAccessDeniedException.class);
    assertThatThrownBy(()->useCase.execute(new RankingActor(actor,true),null,entry,null,20)).isInstanceOf(RankingAccessDeniedException.class);
    assertThatThrownBy(()->useCase.execute(new RankingActor(actor,true),rank,null,null,20)).isInstanceOf(RankingAccessDeniedException.class);
  }

  @Test void oversizedStoragePageIsRejectedBeforePublishing() {
    var useCase=new RankingMovieRoundListUseCase((a,r,e,n,l)->Optional.of(List.of(row(1,null),row(2,false),row(3,true))));
    assertThatThrownBy(()->useCase.execute(new RankingActor(actor,true),rank,entry,null,1))
        .isInstanceOf(RankingAccessDeniedException.class);
  }

  @Test void malformedRowsAndRepeatedIdsInLookaheadFailClosed() {
    var first=row(1,null);
    var invalidRows=Arrays.asList(
        (RankingMovieRoundListItem)null,
        new RankingMovieRoundListItem(null,2,null,Instant.EPOCH,null,false),
        new RankingMovieRoundListItem(UUID.randomUUID(),2,null,null,null,true),
        new RankingMovieRoundListItem(first.roundId(),2,null,Instant.EPOCH,null,null));
    for (var invalid : invalidRows) {
      var useCase=new RankingMovieRoundListUseCase((a,r,e,n,l)->Optional.of(Arrays.asList(first,invalid)));
      assertThatThrownBy(()->useCase.execute(new RankingActor(actor,true),rank,entry,null,1))
          .isInstanceOf(RankingAccessDeniedException.class);
    }
  }

}
