package com.moviemux.rankingcontent;

import static org.assertj.core.api.Assertions.*;
import com.moviemux.rankingcontent.domain.model.*;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class OwnRatingCopyPreviewTest {
  final UUID actor = UUID.randomUUID();
  final RoundIdentity source = identity();
  final RoundIdentity destination = identity();
  final UUID catalogId = UUID.randomUUID();
  final CatalogMovieRound sourceBinding = new CatalogMovieRound(catalogId, source);
  final CatalogMovieRound destinationBinding = new CatalogMovieRound(catalogId, destination);
  static RoundIdentity identity() {
    return new RoundIdentity(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
  }
  RatingValue vote(RoundIdentity round, String score) {
    return new RatingValue(round, actor, new BigDecimal(score));
  }
  @Test void comparesOnlyOwnExplicitRoundsWithoutMutatingSourceOrDestination() {
    var from = vote(source, "8");
    var to = vote(destination, "9");
    var preview = OwnRatingCopyPreview.compare(actor, sourceBinding, from, destinationBinding, to);
    assertThat(preview.status()).isEqualTo(OwnRatingCopyPreview.Status.DESTINATION_CONFLICT);
    assertThat(preview.source()).isEqualTo(sourceBinding);
    assertThat(preview.destination()).isEqualTo(destinationBinding);
    assertThat(from.score()).isEqualByComparingTo("8");
    assertThat(to.score()).isEqualByComparingTo("9");
  }
  @Test void equalDecimalValueDoesNotChooseAnApplyOrXpPolicy() {
    assertThat(OwnRatingCopyPreview.compare(actor, sourceBinding, vote(source, "8"), destinationBinding,
        vote(destination, "8.00")).status()).isEqualTo(OwnRatingCopyPreview.Status.DESTINATION_EQUAL);
    assertThat(OwnRatingCopyPreview.compare(actor, sourceBinding, vote(source, "8"), destinationBinding,
        null).status()).isEqualTo(OwnRatingCopyPreview.Status.DESTINATION_EMPTY);
  }
  @Test void rejectsOtherAuthorsAndWrongRoundEvenWithinDestinationRanking() {
    assertThatIllegalArgumentException().isThrownBy(() -> OwnRatingCopyPreview.compare(
        UUID.randomUUID(), sourceBinding, vote(source, "8"), destinationBinding, null));
    var wrongAuthor = new RatingValue(destination, UUID.randomUUID(), BigDecimal.ONE);
    assertThatIllegalArgumentException().isThrownBy(() -> OwnRatingCopyPreview.compare(
        actor, sourceBinding, vote(source, "8"), destinationBinding, wrongAuthor));
    var wrongRound = new RoundIdentity(destination.rankingId(), destination.entryId(), UUID.randomUUID());
    assertThatIllegalArgumentException().isThrownBy(() -> OwnRatingCopyPreview.compare(
        actor, sourceBinding, vote(source, "8"), destinationBinding, vote(wrongRound, "9")));
  }
  @Test void rejectsSameRankingAndMismatchedCatalogBindings() {
    assertThatIllegalArgumentException().isThrownBy(() -> OwnRatingCopyPreview.compare(
        actor, sourceBinding, vote(source, "8"), sourceBinding, null));
    var differentMovie = new CatalogMovieRound(UUID.randomUUID(), destination);
    assertThatIllegalArgumentException().isThrownBy(() -> OwnRatingCopyPreview.compare(
        actor, sourceBinding, vote(source, "8"), differentMovie, null));
    var wrongSourceBinding = new CatalogMovieRound(catalogId, identity());
    assertThatIllegalArgumentException().isThrownBy(() -> OwnRatingCopyPreview.compare(
        actor, wrongSourceBinding, vote(source, "8"), destinationBinding, null));
  }
}
