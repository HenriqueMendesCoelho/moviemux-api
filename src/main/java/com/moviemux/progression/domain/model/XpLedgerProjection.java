package com.moviemux.progression.domain.model;

import java.math.BigInteger;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Pure replay of a trusted award snapshot. Not a database ledger, concurrent writer or earning rule.
 * Storage adapters must atomically enforce account/event uniqueness and durable award recording.
 * Replay retains one identity per distinct award in memory; it is not an unbounded HTTP batch API.
 */
public record XpLedgerProjection(UUID accountId, BigInteger totalXp, long awardCount) {
  public XpLedgerProjection {
    Objects.requireNonNull(accountId, "accountId");
    Objects.requireNonNull(totalXp, "totalXp");
    if (totalXp.signum() < 0 || awardCount < 0
        || (awardCount == 0) != (totalXp.signum() == 0)) {
      throw new IllegalArgumentException("Invalid award projection");
    }
  }

  public static XpLedgerProjection replay(UUID accountId, Iterable<XpAward> awards) {
    Objects.requireNonNull(accountId, "accountId");
    Objects.requireNonNull(awards, "awards");
    Map<XpEventIdentity, XpAward> accepted = new HashMap<>();
    BigInteger total = BigInteger.ZERO;
    long count = 0;
    for (XpAward award : awards) {
      Objects.requireNonNull(award, "award");
      if (!accountId.equals(award.accountId())) {
        throw new IllegalArgumentException("Award belongs to another progression account");
      }
      XpAward previous = accepted.putIfAbsent(award.event(), award);
      if (previous != null) {
        if (!previous.equals(award)) {
          throw new IllegalArgumentException("Conflicting receipt for the same source event");
        }
        continue;
      }
      total = total.add(award.amount());
      count = Math.incrementExact(count);
    }
    return new XpLedgerProjection(accountId, total, count);
  }
}
