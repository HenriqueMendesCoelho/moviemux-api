package com.moviemux.ranking.domain.model;

import java.util.Objects;
import java.util.UUID;

/** Internal identity. Never use this object as a response, event or log payload. */
public final class RankingAuthorIdentity {
  private final UUID rankingId;
  private final UUID authorId;
  private final UUID accountId;
  private final UUID deletedPublicCode;

  public RankingAuthorIdentity(UUID rankingId, UUID authorId, UUID accountId, UUID deletedPublicCode) {
    this.rankingId = Objects.requireNonNull(rankingId, "rankingId");
    this.authorId = Objects.requireNonNull(authorId, "authorId");
    this.deletedPublicCode = Objects.requireNonNull(deletedPublicCode, "deletedPublicCode");
    if (authorId.equals(deletedPublicCode) || authorId.equals(accountId) || deletedPublicCode.equals(accountId))
      throw new IllegalArgumentException("Contextual identifiers must be independent");
    this.accountId = accountId;
  }

  public UUID rankingId() { return rankingId; }
  public UUID authorId() { return authorId; }
  public UUID accountId() { return accountId; }
  public UUID deletedPublicCode() { return deletedPublicCode; }
  // Deliberately no generated toString, bean getters or JSON annotations in the domain.
}
