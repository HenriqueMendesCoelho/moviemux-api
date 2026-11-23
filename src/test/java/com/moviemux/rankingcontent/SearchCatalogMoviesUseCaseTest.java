package com.moviemux.rankingcontent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.moviemux.ranking.domain.model.RankingActor;
import com.moviemux.rankingcontent.domain.exception.InvalidCatalogMovieSearchException;
import com.moviemux.rankingcontent.domain.exception.RankingEntriesDeniedException;
import com.moviemux.rankingcontent.domain.model.CatalogMovieSearchPage.Item;
import com.moviemux.rankingcontent.domain.usecase.impl.SearchCatalogMoviesUseCaseImpl;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class SearchCatalogMoviesUseCaseTest {
  private final UUID rank = UUID.randomUUID();
  private final RankingActor actor = new RankingActor(UUID.randomUUID(), true);

  @Test void checksInputsAndActorBeforeCatalogRead() {
    var useCase = new SearchCatalogMoviesUseCaseImpl((r, a, q, l, o, n) -> {
      throw new AssertionError("Invalid or unauthorized search must not reach the catalog");
    });
    for (String invalid : new String[] {null, "", "　 \t ", "🎬", "🎬".repeat(121), "ab" + (char) 0 + "cd", "one" + (char) 0xD800}) {
      assertThatThrownBy(() -> useCase.search(actor, rank, invalid, 1, "pt-BR"))
          .isInstanceOf(InvalidCatalogMovieSearchException.class);
    }
    for (int page : new int[] {0, 21}) {
      assertThatThrownBy(() -> useCase.search(actor, rank, "ok", page, "en-US"))
          .isInstanceOf(InvalidCatalogMovieSearchException.class);
    }
    assertThatThrownBy(() -> useCase.search(actor, rank, "ok", 1, "en-GB"))
        .isInstanceOf(InvalidCatalogMovieSearchException.class);
    assertThatThrownBy(() -> useCase.search(new RankingActor(actor.userId(), false), rank, "ok", 1, "en-US"))
        .isInstanceOf(RankingEntriesDeniedException.class);
  }

  @Test void unicodeTrimAndPageLookaheadNeverExposeMoreThanTwenty() {
    var rows = IntStream.range(0, 21).mapToObj(i -> new Item(new UUID(0, i + 1), "Synthetic", null)).toList();
    var useCase = new SearchCatalogMoviesUseCaseImpl((r, a, q, l, o, n) -> {
      assertThat(r).isEqualTo(rank);
      assertThat(a).isEqualTo(actor.userId());
      assertThat(q).isEqualTo("🎬".repeat(120));
      assertThat(l).isEqualTo("pt-BR");
      assertThat(o).isEqualTo(360);
      assertThat(n).isEqualTo(21);
      return rows;
    });
    var page = useCase.search(actor, rank, " 🎬" + "🎬".repeat(119) + "　", 19, "pt-BR");
    assertThat(page.page()).isEqualTo(19);
    assertThat(page.hasMore()).isTrue();
    assertThat(page.items()).containsExactlyElementsOf(rows.subList(0, 20));
  }

  @Test void resultRequiresUsableDisplayTitleWithoutInventingMissingValues() {
    UUID movieId = UUID.randomUUID();
    for (String missing : new String[] {null, "", " ", "\t", "\u00a0", "\u3000"}) {
      assertThatThrownBy(() -> new Item(movieId, missing, null))
          .isInstanceOf(IllegalArgumentException.class);
    }
    var item = new Item(movieId, "Catálogo", null);
    assertThat(item.movieId()).isEqualTo(movieId);
    assertThat(item.title()).isEqualTo("Catálogo");
    assertThat(item.releaseDate()).isNull();
  }

  @Test void twentiethPageDoesNotAskAdapterForTheNextPage() {
    var useCase = new SearchCatalogMoviesUseCaseImpl((r, a, q, l, o, n) -> {
      assertThat(q).isEqualTo("%_");
      assertThat(o).isEqualTo(380);
      assertThat(n).isEqualTo(20);
      return List.of();
    });
    var page = useCase.search(actor, rank, "%_", 20, "en-US");
    assertThat(page.page()).isEqualTo(20);
    assertThat(page.hasMore()).isFalse();
    assertThat(page.items()).isEmpty();
  }
}
