package com.moviemux.user.domain.usecase;


import com.moviemux.user.domain.model.Role;
import com.moviemux.user.domain.exception.UserNotAuthorizedException;
import com.moviemux.user.domain.exception.UserNotFoundException;
import com.moviemux.user.domain.model.User;
import com.moviemux.user.domain.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class FindUserByEmailUseCase {
  private final UserRepository repository;

  public User execute(com.moviemux.user.domain.command.FindUserCommand command)
      throws UserNotFoundException, UserNotAuthorizedException {
    String emailUserToFind = command.targetEmail(), emailUserLoged = command.authenticatedEmail();
    User userLoged = repository.findByEmail(emailUserLoged);
    User user = repository.findByEmail(emailUserToFind);

    if (!emailUserToFind.equals(emailUserLoged) && !userLoged.getRoles().contains(Role.ADM)) {
      throw new UserNotAuthorizedException();
    }

    if (user == null) {
      throw new UserNotFoundException();
    }

    return user;
  }
}
