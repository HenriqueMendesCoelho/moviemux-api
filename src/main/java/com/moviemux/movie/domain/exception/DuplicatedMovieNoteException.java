package com.moviemux.movie.domain.exception;

public class DuplicatedMovieNoteException extends ReflectiveOperationException {
  private static final long serialVersionUID = 1789442948358444674L;

  public DuplicatedMovieNoteException() {
    super("Movie note duplicated");
  }

  public DuplicatedMovieNoteException(String msg) {
    super(msg);
  }
}
