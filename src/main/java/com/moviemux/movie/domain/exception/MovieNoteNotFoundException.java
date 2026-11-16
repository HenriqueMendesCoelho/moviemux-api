package com.moviemux.movie.domain.exception;

public class MovieNoteNotFoundException extends ReflectiveOperationException {

  private static final long serialVersionUID = 4087604245442641655L;

  public MovieNoteNotFoundException() {
    super("Movie note not found");
  }
}
