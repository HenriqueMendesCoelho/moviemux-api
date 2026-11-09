package com.moviemux.user.domain.exception;

public class UserNotAuthorizedException extends ReflectiveOperationException {

  private static final long serialVersionUID = -4896392011913313206L;

  public UserNotAuthorizedException(String msg) {
    super(msg);
  }

  public UserNotAuthorizedException() {
    super("user not authorized to do this action");
  }
}
