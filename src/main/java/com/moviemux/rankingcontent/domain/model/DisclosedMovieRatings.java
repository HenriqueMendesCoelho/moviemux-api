package com.moviemux.rankingcontent.domain.model;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Projection of already authorized, eligible rounds for one movie entry.
 * Hidden votes never contribute to the total, denominator or per-round aggregates.
 * Partial denotes excluded hidden rounds, not their vote count or participation.
 * Transport, sorting, caching and event adapters must consume this projection rather
 * than recomputing an aggregate from the internal snapshots.
 */
public final class DisclosedMovieRatings {
  private final RevealedRatingAggregate aggregate;
  private final Map<UUID, RevealedRatingAggregate> revealedRounds;
  private final boolean partial;

  private DisclosedMovieRatings(RevealedRatingAggregate aggregate,
      Map<UUID, RevealedRatingAggregate> revealedRounds, boolean partial) {
    this.aggregate = aggregate;
    this.revealedRounds = Map.copyOf(revealedRounds);
    this.partial = partial;
  }

  public RevealedRatingAggregate aggregate() { return aggregate; }
  public Map<UUID, RevealedRatingAggregate> revealedRounds() { return revealedRounds; }
  public boolean partial() { return partial; }

  public static DisclosedMovieRatings project(
      UUID rankingId, UUID entryId, Collection<RoundRatingSnapshot> eligibleRounds) {
    Objects.requireNonNull(rankingId, "rankingId");
    Objects.requireNonNull(entryId, "entryId");
    Objects.requireNonNull(eligibleRounds, "eligibleRounds");
    var seen = new HashSet<UUID>();
    var disclosed = new LinkedHashMap<UUID, RevealedRatingAggregate>();
    var votes = new ArrayList<RatingValue>();
    boolean partial = false;
    for (RoundRatingSnapshot snapshot : eligibleRounds) {
      Objects.requireNonNull(snapshot, "snapshot");
      RoundIdentity round = snapshot.identity();
      if (!rankingId.equals(round.rankingId()) || !entryId.equals(round.entryId()))
        throw new IllegalArgumentException("rating.projection.context_mismatch");
      if (!seen.add(round.roundId()))
        throw new IllegalArgumentException("rating.projection.duplicate_round");
      if (snapshot.revealed()) {
        disclosed.put(round.roundId(),
            RevealedRatingAggregate.calculate(rankingId, entryId, snapshot.votes()));
        votes.addAll(snapshot.votes());
      } else {
        partial = true;
      }
    }
    return new DisclosedMovieRatings(
        RevealedRatingAggregate.calculate(rankingId, entryId, votes), disclosed, partial);
  }
}
