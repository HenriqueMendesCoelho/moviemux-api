package com.moviemux.user.domain.usecase;

import com.moviemux.user.domain.Role;
import com.moviemux.user.domain.command.UpdateAccountCommand;
import com.moviemux.user.domain.exception.UserNotAuthorizedException;
import com.moviemux.user.domain.exception.UserNotFoundException;
import com.moviemux.user.domain.model.User;
import com.moviemux.user.domain.repository.UserRepository;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class UpdateAccountUseCase {
  private final UserRepository repository;

  public User execute(UpdateAccountCommand command)
      throws UserNotFoundException, UserNotAuthorizedException {
    User user = command.user();
    UUID userId = command.userId();
    String emailUserLoged = command.emailUserLoged();
    User userLoged = repository.findByEmail(emailUserLoged);
    User userToUpdate = repository.findById(userId).orElse(null);

    if (userLoged.getId() != userId && !userLoged.getRoles().contains(Role.ADM)) {
      throw new UserNotAuthorizedException();
    }

    if (userToUpdate == null) {
      throw new UserNotFoundException();
    }

    userToUpdate.setEmail(user.getEmail());
    userToUpdate.setName(user.getName());
    userToUpdate.getPreferences().setNotify(user.getPreferences().isNotify());

    return repository.save(userToUpdate);
  }
}
