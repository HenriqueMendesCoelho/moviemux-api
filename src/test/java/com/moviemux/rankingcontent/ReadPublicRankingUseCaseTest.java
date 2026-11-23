package com.moviemux.rankingcontent;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.moviemux.rankingcontent.domain.exception.InvalidPublicRankingPageException;
import com.moviemux.rankingcontent.domain.exception.RankingEntriesDeniedException;
import com.moviemux.rankingcontent.domain.model.PublicRankingEntry;
import com.moviemux.rankingcontent.domain.model.PublicRankingSummary;
import com.moviemux.rankingcontent.domain.port.ReadPublicRankingProjection;
import com.moviemux.rankingcontent.domain.usecase.impl.ReadPublicRankingUseCaseImpl;
import java.math.BigInteger;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ReadPublicRankingUseCaseTest {
  private static final UUID RANK = new UUID(0, 1), OTHER = new UUID(0, 2);
  private static final UUID FIRST = UUID.fromString("7fffffff-ffff-ffff-ffff-ffffffffffff");
  private static final UUID LAST = UUID.fromString("80000000-0000-0000-0000-000000000000");
  private final ReadPublicRankingProjection port = mock(ReadPublicRankingProjection.class);
  private final ReadPublicRankingUseCaseImpl useCase = new ReadPublicRankingUseCaseImpl(port);

  @Test void missingMalformedOrMismatchedScopeIsUniform() {
    assertThatThrownBy(() -> useCase.summary(null)).isInstanceOf(RankingEntriesDeniedException.class);
    assertThatThrownBy(() -> useCase.entries(null, null, 20)).isInstanceOf(RankingEntriesDeniedException.class);
    verifyNoInteractions(port);
    when(port.summary(RANK)).thenReturn(null, Optional.empty(), Optional.of(new PublicRankingSummary(OTHER,"wrong")));
    for(int i=0;i<3;i++)
      assertThatThrownBy(() -> useCase.summary(RANK)).isInstanceOf(RankingEntriesDeniedException.class);
    when(port.summary(RANK)).thenReturn(Optional.of(new PublicRankingSummary(RANK,null)));
    assertThat(useCase.summary(RANK)).isEqualTo(new PublicRankingSummary(RANK,null));
  }

  @Test void invalidLimitOnlyDisclosesBadRequestForCurrentPublicRank() {
    when(port.summary(RANK)).thenReturn(Optional.empty(), Optional.of(new PublicRankingSummary(RANK,null)));
    assertThatThrownBy(() -> useCase.entries(RANK,null,0)).isInstanceOf(RankingEntriesDeniedException.class);
    assertThatThrownBy(() -> useCase.entries(RANK,null,51)).isInstanceOf(InvalidPublicRankingPageException.class);
    verify(port,times(2)).summary(RANK);
    verifyNoMoreInteractions(port);
  }

  @Test void paginationUnsignedUuidAndExactDisclosedTalliesNeverRequireSeparateSummary() {
    var entry = new PublicRankingEntry(FIRST,null,BigInteger.ONE.shiftLeft(128).negate(),BigInteger.ONE.shiftLeft(96));
    var last = row(LAST);
    when(port.entries(RANK,null,2)).thenReturn(Optional.of(List.of(entry,last)));
    when(port.entries(RANK,FIRST,2)).thenReturn(Optional.of(List.of(last)));
    when(port.entries(RANK,LAST,51)).thenReturn(Optional.of(List.of()));
    var first = useCase.entries(RANK,null,1);
    assertThat(first.items()).containsExactly(entry);
    assertThat(first.nextCursor()).isEqualTo(FIRST);
    assertThatThrownBy(() -> first.items().clear()).isInstanceOf(UnsupportedOperationException.class);
    var second = useCase.entries(RANK,FIRST,1);
    assertThat(second.items()).containsExactly(last);
    assertThat(second.nextCursor()).isNull();
    var empty = useCase.entries(RANK,LAST,50);
    assertThat(empty.items()).isEmpty();
    assertThat(empty.nextCursor()).isNull();
    verify(port,never()).summary(any());
  }

  @Test void invalidPortPageOrLookaheadFailsClosed() {
    for(Optional<List<PublicRankingEntry>> result : Arrays.asList(null,Optional.<List<PublicRankingEntry>>empty(),
        Optional.of(List.of(row(FIRST),row(LAST),row(new UUID(-1,-1)))),
        Optional.of(Arrays.asList(row(FIRST),null)),
        Optional.of(List.of(row(null))),
        Optional.of(List.of(new PublicRankingEntry(FIRST,null,null,BigInteger.ONE))),
        Optional.of(List.of(new PublicRankingEntry(FIRST,null,BigInteger.ZERO,null))),
        Optional.of(List.of(new PublicRankingEntry(FIRST,null,BigInteger.ZERO,BigInteger.ONE.negate()))),
        Optional.of(List.of(new PublicRankingEntry(FIRST,null,BigInteger.ONE,BigInteger.ZERO))),
        Optional.of(List.of(row(FIRST),row(FIRST))),Optional.of(List.of(row(LAST),row(FIRST))))) {
      when(port.entries(RANK,null,2)).thenReturn(result);
      assertThatThrownBy(() -> useCase.entries(RANK,null,1)).isInstanceOf(RankingEntriesDeniedException.class);
    }
    when(port.entries(RANK,FIRST,2)).thenReturn(Optional.of(List.of(row(FIRST))));
    assertThatThrownBy(() -> useCase.entries(RANK,FIRST,1)).isInstanceOf(RankingEntriesDeniedException.class);
  }

  private static PublicRankingEntry row(UUID id) { return new PublicRankingEntry(id,"Film",BigInteger.ZERO,BigInteger.ZERO); }
}
