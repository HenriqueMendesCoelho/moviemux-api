package com.moviemux.rankingcontent.domain.exception;

/** Typed transport/response failure, deliberately without remote payload, message or cause. */
public final class TmdbProviderFailureException extends RuntimeException {
  public enum Kind { REMOTE_STATUS, INVALID_RESPONSE, TRANSPORT }

  private final Kind kind;
  private final Integer status;

  private TmdbProviderFailureException(Kind kind, Integer status) {
    this.kind = kind;
    this.status = status;
  }

  public static TmdbProviderFailureException remoteStatus(int status) {
    return new TmdbProviderFailureException(Kind.REMOTE_STATUS, status);
  }

  public static TmdbProviderFailureException invalidResponse() {
    return new TmdbProviderFailureException(Kind.INVALID_RESPONSE, null);
  }

  public static TmdbProviderFailureException transport() {
    return new TmdbProviderFailureException(Kind.TRANSPORT, null);
  }

  public Kind kind() { return kind; }
  public Integer status() { return status; }
}
