package com.moviemux.user.domain.exception;

public class DuplicatedUserException extends ReflectiveOperationException {

  private static final long serialVersionUID = -7794481618234419464L;

  public DuplicatedUserException() {
    super("user email already used");
  }
}
