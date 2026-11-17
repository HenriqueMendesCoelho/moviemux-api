package com.moviemux.progression.domain.model;

import java.math.BigInteger;
import java.util.Objects;
import java.util.UUID;

/**
 * Receipt of a previously authorized, committed award, not an incoming client command. The account
 * is opaque: account-wide versus per-ranking scope must be decided by the caller's approved policy.
 * This value does not establish eligibility, authorize an action or implement revocation semantics.
 */
public record XpAward(
    UUID accountId, XpEventIdentity event, String policyVersion, BigInteger amount) {
  public XpAward {
    Objects.requireNonNull(accountId, "accountId");
    Objects.requireNonNull(event, "event");
    Objects.requireNonNull(policyVersion, "policyVersion");
    Objects.requireNonNull(amount, "amount");
    if (policyVersion.isBlank() || amount.signum() <= 0) {
      throw new IllegalArgumentException("An award needs a policy version and positive base units");
    }
  }
}
