package com.moviemux.user.domain.exception;

public class UserRedefinePasswordKeyNotFound extends ReflectiveOperationException {

  private static final long serialVersionUID = 7130451935243291369L;

  public UserRedefinePasswordKeyNotFound() {
    super("user Redefine Password Key not found");
  }
}
