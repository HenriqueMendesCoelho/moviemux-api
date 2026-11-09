package com.moviemux.user.adapter.controller;

import com.moviemux.user.domain.command.FindUserCommand;
import com.moviemux.user.domain.exception.UserNotAuthorizedException;
import com.moviemux.user.domain.exception.UserNotFoundException;
import com.moviemux.user.domain.model.User;
import com.moviemux.user.domain.usecase.FindUserByEmailUseCase;
import com.moviemux.user.domain.usecase.ListUsersUseCase;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class UserQueryOperations {
  private final FindUserByEmailUseCase find;
  private final ListUsersUseCase list;

  public User getUserByEmail(String targetEmail, String authenticatedEmail)
      throws UserNotFoundException, UserNotAuthorizedException {
    return find.execute(new FindUserCommand(targetEmail, authenticatedEmail));
  }

  public List<User> getAllUsers() throws UserNotFoundException {
    return list.execute();
  }
}
