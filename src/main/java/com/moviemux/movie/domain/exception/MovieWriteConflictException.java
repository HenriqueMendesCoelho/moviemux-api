package com.moviemux.movie.domain.exception;

/** A canonical identifier is unavailable; never exposes the conflicting row or SQL error. */
public final class MovieWriteConflictException extends RuntimeException {
  public MovieWriteConflictException() {
    super("Movie duplicated");
  }
}
