package com.moviemux.mail.domain.exception;

/** Sanitized provider failure: never includes remote bodies, addresses or reset keys. */
public class MailDeliveryFailure extends RuntimeException {
  public enum Reason {
    HTTP_ERROR,
    UNAVAILABLE,
    INVALID_RESPONSE
  }

  private final Reason reason;

  public MailDeliveryFailure(Reason reason) {
    super("Mail provider request failed: " + reason);
    this.reason = reason;
  }

  public Reason reason() {
    return reason;
  }
}
