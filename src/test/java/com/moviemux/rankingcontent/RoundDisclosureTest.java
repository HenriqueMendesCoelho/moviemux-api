package com.moviemux.rankingcontent;

import static org.assertj.core.api.Assertions.*;

import com.moviemux.rankingcontent.domain.model.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RoundDisclosureTest {
  private final RoundIdentity identity = new RoundIdentity(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
  private final Instant createdAt = Instant.parse("2026-11-17T12:00:00Z");
  private final RoundDisclosure hidden = new RoundDisclosure(identity, createdAt, false);

  @Test void legacyDetailRevealsAtExactThirtyMinuteBoundary() {
    assertThat(hidden.onLegacyDetailRead(createdAt.minusSeconds(1))).isSameAs(hidden);
    assertThat(hidden.onLegacyDetailRead(createdAt)).isSameAs(hidden);
    assertThat(hidden.onLegacyDetailRead(createdAt.plusSeconds(1800).minusNanos(1))).isSameAs(hidden);
    var revealed = hidden.onLegacyDetailRead(createdAt.plusSeconds(1800));
    assertThat(revealed.revealed()).isTrue();
    assertThat(revealed.identity()).isEqualTo(identity);
    assertThat(revealed.createdAt()).isEqualTo(createdAt);
    assertThat(hidden.revealed()).isFalse();
    assertThat(hidden.onLegacyDetailRead(createdAt.plusSeconds(1801)).revealed()).isTrue();
  }

  @Test void alreadyRevealedDoesNotReopenEvenWithAnEarlierClockReading() {
    var revealed = new RoundDisclosure(identity, createdAt, true);
    assertThat(revealed.onLegacyDetailRead(createdAt.minusSeconds(1))).isSameAs(revealed);
    assertThat(revealed.onLegacyDetailRead(createdAt.plusSeconds(3600))).isSameAs(revealed);
  }

  @Test void snapshotsDoNotAdvanceDisclosureOrPublishOwnHiddenVote() {
    var ownVote = new RatingValue(identity, UUID.randomUUID(), new BigDecimal("8"));
    var result = DisclosedMovieRatings.project(identity.rankingId(), identity.entryId(),
        List.of(hidden.snapshot(List.of(ownVote))));
    assertThat(result.aggregate().hasVotes()).isFalse();
    assertThat(result.partial()).isTrue();
    var revealed = hidden.onLegacyDetailRead(createdAt.plusSeconds(1800));
    var after = DisclosedMovieRatings.project(identity.rankingId(), identity.entryId(),
        List.of(revealed.snapshot(List.of(ownVote))));
    assertThat(after.aggregate().total()).isEqualByComparingTo("8");
    assertThat(after.partial()).isFalse();
  }

  @Test void anotherRoundAndAnotherRankingKeepIndependentClocks() {
    var anotherRound = new RoundDisclosure(new RoundIdentity(identity.rankingId(), identity.entryId(), UUID.randomUUID()), createdAt.plusSeconds(900), false);
    var anotherRanking = new RoundDisclosure(new RoundIdentity(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID()), createdAt.plusSeconds(1200), false);
    var now = createdAt.plusSeconds(1800);
    assertThat(hidden.onLegacyDetailRead(now).revealed()).isTrue();
    assertThat(anotherRound.onLegacyDetailRead(now).revealed()).isFalse();
    assertThat(anotherRanking.onLegacyDetailRead(now).revealed()).isFalse();
  }

  @Test void timestampExtremesDoNotOverflowAndNullsAreRejected() {
    assertThat(new RoundDisclosure(identity, Instant.MAX, false).onLegacyDetailRead(Instant.MAX).revealed()).isFalse();
    assertThat(new RoundDisclosure(identity, Instant.MIN, false).onLegacyDetailRead(Instant.MAX).revealed()).isTrue();
    assertThatNullPointerException().isThrownBy(() -> new RoundDisclosure(null, createdAt, false));
    assertThatNullPointerException().isThrownBy(() -> new RoundDisclosure(identity, null, false));
    assertThatNullPointerException().isThrownBy(() -> hidden.onLegacyDetailRead(null));
  }

  @Test void contextualSnapshotRejectsVotesOfAnotherRound() {
    var other = new RoundIdentity(identity.rankingId(), identity.entryId(), UUID.randomUUID());
    assertThatIllegalArgumentException().isThrownBy(() ->
        hidden.snapshot(List.of(new RatingValue(other, UUID.randomUUID(), BigDecimal.ONE))));
  }
}
