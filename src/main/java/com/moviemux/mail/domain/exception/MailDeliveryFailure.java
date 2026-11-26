package com.moviemux.mail.domain.exception;

/** Sanitized provider failure: never includes remote bodies, addresses or reset keys. */
public class MailDeliveryFailure extends RuntimeException {
  public enum Reason {
    HTTP_ERROR,
    UNAVAILABLE,
    INVALID_RESPONSE
  }

  private final Reason reason;
  private final Integer httpStatus;

  public MailDeliveryFailure(Reason reason) {
    this(reason,null);
  }

  public MailDeliveryFailure(Reason reason,Integer httpStatus) {
    super("Mail provider request failed: " + reason);
    this.reason = reason;
    this.httpStatus = httpStatus;
  }

  /** A 4xx negative gateway response is distinct from an ambiguous 5xx/transport outcome. */
  public boolean explicitRejection() {
    return reason==Reason.HTTP_ERROR && httpStatus!=null && httpStatus>=400 && httpStatus<500
        && httpStatus!=408 && httpStatus!=409;
  }

  public Reason reason() {
    return reason;
  }
}
