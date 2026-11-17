package com.moviemux.rankingcontent;

import static org.assertj.core.api.Assertions.*;

import com.moviemux.rankingcontent.domain.model.*;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.time.DateTimeException;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RankingContentTest {
  private final UUID ranking = UUID.randomUUID();
  private final UUID entry = UUID.randomUUID();
  private final UUID round = UUID.randomUUID();
  private final UUID author = UUID.randomUUID();
  private RatingValue vote(UUID roundId, UUID user, String score) {
    return new RatingValue(new RoundIdentity(ranking, entry, roundId), user, new BigDecimal(score));
  }

  @Test void aggregatesVotesNotRoundAveragesAndRetainsSameAuthorAcrossRounds() {
    var result = RevealedRatingAggregate.calculate(ranking, entry,
        List.of(vote(round, author, "8"), vote(round, UUID.randomUUID(), "6"),
            vote(UUID.randomUUID(), author, "10")));
    assertThat(result.total()).isEqualByComparingTo("24");
    assertThat(result.votes()).isEqualTo(BigInteger.valueOf(3));
    assertThat(result.hasVotes()).isTrue();
  }

  @Test void emptyVotesDoNotManufactureZeroRating() {
    var result = RevealedRatingAggregate.calculate(ranking, entry, List.of());
    assertThat(result.hasVotes()).isFalse();
    assertThat(result.votes()).isZero();
  }

  @Test void aggregateRejectsForeignRankingOrEntryAndDuplicateVotes() {
    var value = vote(round, author, "8");
    assertThatIllegalArgumentException().isThrownBy(() ->
        RevealedRatingAggregate.calculate(UUID.randomUUID(), entry, List.of(value)));
    assertThatIllegalArgumentException().isThrownBy(() ->
        RevealedRatingAggregate.calculate(ranking, UUID.randomUUID(), List.of(value)));
    assertThatIllegalArgumentException().isThrownBy(() ->
        RevealedRatingAggregate.calculate(ranking, entry, List.of(value, value)));
  }

  @Test void decimalScoresRemainExactAndNoDisplayScaleIsAssumed() {
    var result = RevealedRatingAggregate.calculate(ranking, entry,
        List.of(vote(round, author, "0.1234567890123456789"),
            vote(round, UUID.randomUUID(), "0.0000000000000000001")));
    assertThat(result.total()).isEqualByComparingTo("0.1234567890123456790");
  }

  @Test void roundIdentityAndStableNumberAreRequiredButDescriptionIsOptional() {
    var identity = new RoundIdentity(ranking, entry, round);
    assertThat(new RankingRound(identity, 1, null, Instant.EPOCH).description()).isNull();
    assertThatIllegalArgumentException().isThrownBy(() -> new RankingRound(identity, 0, null, Instant.EPOCH));
    assertThatNullPointerException().isThrownBy(() -> new RoundIdentity(null, entry, round));
    assertThatNullPointerException().isThrownBy(() -> new RoundIdentity(ranking, null, round));
    assertThatNullPointerException().isThrownBy(() -> new RoundIdentity(ranking, entry, null));
  }

  @Test void recoveryEndsAtExact24HourDeadlineAndPurgeNeverStartsEarly() {
    Instant now = Instant.parse("2026-11-17T12:00:00Z");
    var removal = new RecoverableRemoval(now);
    Instant deadline = now.plusSeconds(86400);
    assertThat(removal.recoverableUntil()).isEqualTo(deadline);
    assertThat(removal.canRestoreAt(now.minusNanos(1))).isFalse();
    assertThat(removal.canRestoreAt(now)).isTrue();
    assertThat(removal.canRestoreAt(deadline.minusNanos(1))).isTrue();
    assertThat(removal.canPurgeAt(deadline.minusNanos(1))).isFalse();
    assertThat(removal.canRestoreAt(deadline)).isFalse();
    assertThat(removal.canPurgeAt(deadline)).isTrue();
    assertThat(removal.canRestoreAt(deadline.plusSeconds(1))).isFalse();
    assertThat(removal.canPurgeAt(deadline.plusSeconds(1))).isTrue();
  }

  @Test void rejectsNullAndOverflowingRemovalInstants() {
    assertThatNullPointerException().isThrownBy(() -> new RecoverableRemoval(null));
    assertThatExceptionOfType(DateTimeException.class).isThrownBy(() -> new RecoverableRemoval(Instant.MAX));
  }
}
