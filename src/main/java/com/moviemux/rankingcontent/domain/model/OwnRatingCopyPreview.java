package com.moviemux.rankingcontent.domain.model;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;

/** Side-effect-free comparison of already authorized snapshots, not an apply command.
 * Both exact rounds and source ownership are mandatory. Eligibility, scale compatibility,
 * version checks and authorization must be verified by the application before using it.
 * Existing destination scores are preserved; no replacement/XP policy is selected here.
 */
public final class OwnRatingCopyPreview {
  public enum Status { DESTINATION_EMPTY, DESTINATION_EQUAL, DESTINATION_CONFLICT }
  private final UUID actorId;
  private final CatalogMovieRound source;
  private final CatalogMovieRound destination;
  private final BigDecimal sourceScore;
  private final BigDecimal destinationScore;
  private final Status status;

  private OwnRatingCopyPreview(UUID actorId, CatalogMovieRound source,
      CatalogMovieRound destination, BigDecimal sourceScore, BigDecimal destinationScore) {
    this.actorId = actorId;
    this.source = source;
    this.destination = destination;
    this.sourceScore = sourceScore;
    this.destinationScore = destinationScore;
    this.status = classify(sourceScore, destinationScore);
  }
  public UUID actorId() { return actorId; }
  public CatalogMovieRound source() { return source; }
  public CatalogMovieRound destination() { return destination; }
  public BigDecimal sourceScore() { return sourceScore; }
  public BigDecimal destinationScore() { return destinationScore; }
  public Status status() { return status; }

  public static OwnRatingCopyPreview compare(
      UUID actorId, CatalogMovieRound sourceBinding, RatingValue source,
      CatalogMovieRound destination, RatingValue existingDestination) {
    Objects.requireNonNull(source, "source");
    if (!source.authorId().equals(Objects.requireNonNull(actorId, "actorId")))
      throw new IllegalArgumentException("rating.copy.not_owner");
    Objects.requireNonNull(destination, "destination");
    Objects.requireNonNull(sourceBinding, "sourceBinding");
    if (!sourceBinding.round().equals(source.round()) ||
        !sourceBinding.catalogMovieId().equals(destination.catalogMovieId()))
      throw new IllegalArgumentException("rating.copy.catalog_mismatch");
    if (source.round().rankingId().equals(destination.round().rankingId()))
      throw new IllegalArgumentException("rating.copy.same_ranking");
    if (existingDestination != null &&
        (!actorId.equals(existingDestination.authorId()) || !destination.round().equals(existingDestination.round())))
      throw new IllegalArgumentException("rating.copy.destination_mismatch");
    BigDecimal existing = existingDestination == null ? null : existingDestination.score();
    return new OwnRatingCopyPreview(actorId, sourceBinding, destination, source.score(), existing);
  }

  private static Status classify(BigDecimal source, BigDecimal destination) {
    return destination == null ? Status.DESTINATION_EMPTY :
        source.compareTo(destination) == 0 ? Status.DESTINATION_EQUAL : Status.DESTINATION_CONFLICT;
  }
}
