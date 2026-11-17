package com.moviemux.rankingcontent.domain.model;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Collection;
import java.util.HashSet;
import java.util.Objects;
import java.util.UUID;

/** Exact sum/count for already authorized, eligible, fully revealed votes only.
 * No rounded average or public DTO is produced before disclosure policy is approved.
 */
public record RevealedRatingAggregate(BigDecimal total, BigInteger votes) {
  public RevealedRatingAggregate {
    Objects.requireNonNull(total, "total");
    Objects.requireNonNull(votes, "votes");
    if (votes.signum() < 0 || (votes.signum() == 0 && total.signum() != 0))
      throw new IllegalArgumentException("rating.aggregate.invalid");
  }

  public static RevealedRatingAggregate calculate(
      UUID rankingId, UUID entryId, Collection<RatingValue> eligibleRevealedVotes) {
    Objects.requireNonNull(rankingId, "rankingId");
    Objects.requireNonNull(entryId, "entryId");
    Objects.requireNonNull(eligibleRevealedVotes, "eligibleRevealedVotes");
    BigDecimal sum = BigDecimal.ZERO;
    BigInteger count = BigInteger.ZERO;
    var keys = new HashSet<VoteKey>();
    for (RatingValue vote : eligibleRevealedVotes) {
      Objects.requireNonNull(vote, "vote");
      if (!rankingId.equals(vote.round().rankingId()) || !entryId.equals(vote.round().entryId()))
        throw new IllegalArgumentException("rating.aggregate.context_mismatch");
      if (!keys.add(new VoteKey(vote.round().roundId(), vote.authorId())))
        throw new IllegalArgumentException("rating.aggregate.duplicate_vote");
      sum = sum.add(vote.score());
      count = count.add(BigInteger.ONE);
    }
    return new RevealedRatingAggregate(sum, count);
  }

  public boolean hasVotes() { return votes.signum() != 0; }
  private record VoteKey(UUID roundId, UUID authorId) {}
}
