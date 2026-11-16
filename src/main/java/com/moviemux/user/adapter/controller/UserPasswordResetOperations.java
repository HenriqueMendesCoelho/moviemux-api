package com.moviemux.user.adapter.controller;

import com.moviemux.user.domain.command.RedeemPasswordResetCommand;
import com.moviemux.user.domain.exception.UserNotAuthorizedException;
import com.moviemux.user.domain.exception.UserRedefinePasswordKeyInvalid;
import com.moviemux.user.domain.exception.UserRedefinePasswordKeyNotFound;
import com.moviemux.user.domain.model.User;
import com.moviemux.user.domain.usecase.RedeemPasswordResetUseCase;
import com.moviemux.user.domain.usecase.RequestPasswordResetUseCase;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class UserPasswordResetOperations {
  private final RequestPasswordResetUseCase request;
  private final RedeemPasswordResetUseCase redeem;

  public void createRedefinePassword(String email) {
    request.execute(email);
  }

  public User redefine(String key, String email, String password)
      throws UserRedefinePasswordKeyNotFound,
          UserRedefinePasswordKeyInvalid,
          UserNotAuthorizedException {
    return redeem.execute(new RedeemPasswordResetCommand(key, email, password));
  }
}
