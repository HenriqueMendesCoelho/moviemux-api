package com.moviemux.user.domain.usecase;

import com.moviemux.user.domain.exception.DuplicatedUserException;
import com.moviemux.user.domain.exception.InviteNotValidException;
import com.moviemux.user.domain.model.User;

public interface CreateUserUseCase {

  User execute(com.moviemux.user.domain.command.RegisterUserCommand command)
      throws DuplicatedUserException, InviteNotValidException;
}
