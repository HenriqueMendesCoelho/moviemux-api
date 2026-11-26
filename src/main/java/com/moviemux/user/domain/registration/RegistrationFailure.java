package com.moviemux.user.domain.registration;

/** Transport-independent sanitized registration outcomes. */
public final class RegistrationFailure extends RuntimeException {
  public enum Reason {
    INVALID_REQUEST, FORBIDDEN, MODE_CHANGED, REQUEST_NOT_FOUND,
    INVITATION_IN_PROGRESS, INVITATION_REVOKED, UNAVAILABLE
  }
  private final Reason reason;
  public RegistrationFailure(Reason reason) {
    super(reason.name());
    this.reason = reason;
  }
  public Reason reason() { return reason; }
}
