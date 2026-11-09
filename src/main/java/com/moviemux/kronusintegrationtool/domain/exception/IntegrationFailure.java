package com.moviemux.kronusintegrationtool.domain.exception;

/** A remote failure is never equivalent to an empty catalog or a disabled integration. */
public final class IntegrationFailure extends RuntimeException {
  public enum Kind {
    REMOTE_STATUS,
    INVALID_RESPONSE,
    TRANSPORT
  }

  private final Kind kind;
  private final Integer status;

  public IntegrationFailure(Kind kind, Integer status, Throwable cause) {
    super("External integration failed", cause);
    this.kind = kind;
    this.status = status;
  }

  public Kind getKind() {
    return kind;
  }

  public Integer getStatus() {
    return status;
  }
}
