package com.moviemux.user.domain.usecase;

import com.moviemux.user.domain.command.UpdateProfileCommand;
import com.moviemux.user.domain.exception.UserNotFoundException;
import com.moviemux.user.domain.model.User;
import com.moviemux.user.domain.repository.UserRepository;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class UpdateProfileUseCase {
  private final UserRepository repository;

  public User execute(UpdateProfileCommand command) throws UserNotFoundException {
    UUID userId = command.userId();
    String name = command.name();
    String email = command.email();
    boolean notify = command.notificationsEnabled();
    User userToUpdate = repository.findById(userId).orElse(null);

    if (userToUpdate == null) {
      throw new UserNotFoundException();
    }

    userToUpdate.setName(name);
    userToUpdate.setEmail(email);
    userToUpdate.getPreferences().setNotify(notify);

    return repository.save(userToUpdate);
  }
}
