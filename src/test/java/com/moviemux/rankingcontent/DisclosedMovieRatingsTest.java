package com.moviemux.rankingcontent;

import static org.assertj.core.api.Assertions.*;

import com.moviemux.rankingcontent.domain.model.*;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DisclosedMovieRatingsTest {
  private final UUID ranking = UUID.randomUUID();
  private final UUID entry = UUID.randomUUID();
  private final UUID author = UUID.randomUUID();
  private final RoundIdentity first = new RoundIdentity(ranking, entry, UUID.randomUUID());
  private final RoundIdentity second = new RoundIdentity(ranking, entry, UUID.randomUUID());

  private RatingValue vote(RoundIdentity round, UUID user, String value) {
    return new RatingValue(round, user, new BigDecimal(value));
  }

  private RoundRatingSnapshot round(RoundIdentity id, boolean revealed, String... values) {
    var votes = new ArrayList<RatingValue>();
    for (int i = 0; i < values.length; i++)
      votes.add(vote(id, i == 0 ? author : UUID.randomUUID(), values[i]));
    return new RoundRatingSnapshot(id, revealed, votes);
  }

  private DisclosedMovieRatings project(RoundRatingSnapshot... rounds) {
    return DisclosedMovieRatings.project(ranking, entry, List.of(rounds));
  }

  @Test void mixedRoundsPublishOnlyRevealedSubtotalAndMatchingDenominator() {
    var result = project(round(first, true, "8", "6"), round(second, false, "10"));
    assertThat(result.aggregate().total()).isEqualByComparingTo("14");
    assertThat(result.aggregate().votes()).isEqualTo(BigInteger.TWO);
    assertThat(result.revealedRounds()).containsOnlyKeys(first.roundId());
    assertThat(result.partial()).isTrue();
  }

  @Test void hiddenScoresCountsAndEditsCannotChangeProjection() {
    var visible = round(first, true, "8", "6");
    var before = project(visible, round(second, false));
    for (String score : List.of("0", "3.123456789", "10")) {
      assertThat(project(visible, round(second, false, score))).usingRecursiveComparison().isEqualTo(before);
      assertThat(project(visible, round(second, false, score, "9", "1"))).usingRecursiveComparison().isEqualTo(before);
    }
    // Removing all hidden votes and restoring them also leaves the projection unchanged.
    assertThat(project(visible, round(second, false))).usingRecursiveComparison().isEqualTo(before);
  }

  @Test void revelationAddsVotesWithEqualWeightRatherThanAveragingRoundMeans() {
    var result = project(round(first, true, "8", "6"), round(second, true, "10"));
    assertThat(result.aggregate().total()).isEqualByComparingTo("24");
    assertThat(result.aggregate().votes()).isEqualTo(BigInteger.valueOf(3));
    assertThat(result.partial()).isFalse();
    assertThat(result.revealedRounds()).hasSize(2);
    // The same author contributes once per round, rather than being deduplicated globally.
    assertThat(result.revealedRounds().get(second.roundId()).votes()).isEqualTo(BigInteger.ONE);
  }

  @Test void onlyHiddenOrEmptyRoundsHaveNoVotesAndNoArtificialZeroRating() {
    var hidden = project(round(first, false, "8"));
    assertThat(hidden.aggregate().hasVotes()).isFalse();
    assertThat(hidden.revealedRounds()).isEmpty();
    assertThat(hidden.partial()).isTrue();
    var empty = project();
    assertThat(empty.aggregate().hasVotes()).isFalse();
    assertThat(empty.partial()).isFalse();
    var visibleEmpty = project(round(first, true));
    assertThat(visibleEmpty.aggregate().hasVotes()).isFalse();
    assertThat(visibleEmpty.revealedRounds().get(first.roundId()).hasVotes()).isFalse();
  }

  @Test void revealedEditsRemovalAndRestoreReconcileOnlyEligibleVotes() {
    var hidden = round(second, false, "10");
    assertThat(project(round(first, true, "8"), hidden).aggregate().total()).isEqualByComparingTo("8");
    assertThat(project(round(first, true, "9"), hidden).aggregate().total()).isEqualByComparingTo("9");
    assertThat(project(round(first, true), hidden).aggregate().hasVotes()).isFalse();
    assertThat(project(round(first, true, "8"), hidden).aggregate().total()).isEqualByComparingTo("8");
  }

  @Test void rejectsCrossRankingAndCrossEntryEvenForHiddenRounds() {
    for (var id : List.of(new RoundIdentity(UUID.randomUUID(), entry, UUID.randomUUID()),
        new RoundIdentity(ranking, UUID.randomUUID(), UUID.randomUUID()))) {
      assertThatIllegalArgumentException().isThrownBy(() -> project(round(id, false, "8")))
          .withMessage("rating.projection.context_mismatch");
      assertThatIllegalArgumentException().isThrownBy(() -> project(round(id, true, "8")))
          .withMessage("rating.projection.context_mismatch");
    }
  }

  @Test void rejectsDuplicateRoundsAndDuplicateRevealedVotes() {
    var visible = round(first, true, "8");
    assertThatIllegalArgumentException().isThrownBy(() -> project(visible, visible))
        .withMessage("rating.projection.duplicate_round");
    assertThatIllegalArgumentException().isThrownBy(() -> project(visible, round(first, false)))
        .withMessage("rating.projection.duplicate_round");
    var vote = vote(first, author, "8");
    assertThatIllegalArgumentException().isThrownBy(() ->
        project(new RoundRatingSnapshot(first, true, List.of(vote, vote))))
        .withMessage("rating.aggregate.duplicate_vote");
  }

  @Test void snapshotRejectsForeignVotesAndNullsAndCopiesMutableInputs() {
    assertThatIllegalArgumentException().isThrownBy(() ->
        new RoundRatingSnapshot(first, false, List.of(vote(second, author, "8"))));
    assertThatNullPointerException().isThrownBy(() -> new RoundRatingSnapshot(null, true, List.of()));
    assertThatNullPointerException().isThrownBy(() -> new RoundRatingSnapshot(first, true, null));
    var mutable = new ArrayList<RatingValue>();
    mutable.add(vote(first, author, "8"));
    var snapshot = new RoundRatingSnapshot(first, true, mutable);
    mutable.clear();
    assertThat(snapshot.votes()).hasSize(1);
    assertThatExceptionOfType(UnsupportedOperationException.class).isThrownBy(() -> snapshot.votes().clear());
    assertThatExceptionOfType(UnsupportedOperationException.class).isThrownBy(() -> project(snapshot).revealedRounds().clear());
  }

  @Test void rejectsNullProjectionInputs() {
    assertThatNullPointerException().isThrownBy(() -> DisclosedMovieRatings.project(null, entry, List.of()));
    assertThatNullPointerException().isThrownBy(() -> DisclosedMovieRatings.project(ranking, null, List.of()));
    assertThatNullPointerException().isThrownBy(() -> DisclosedMovieRatings.project(ranking, entry, null));
    assertThatNullPointerException().isThrownBy(() ->
        DisclosedMovieRatings.project(ranking, entry, java.util.Arrays.asList((RoundRatingSnapshot) null)));
  }
}
