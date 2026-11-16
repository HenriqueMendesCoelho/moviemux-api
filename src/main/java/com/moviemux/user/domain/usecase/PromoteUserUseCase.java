package com.moviemux.user.domain.usecase;

import com.moviemux.user.domain.model.Role;
import com.moviemux.user.domain.command.PromoteUserCommand;
import com.moviemux.user.domain.exception.UserNotFoundException;
import com.moviemux.user.domain.model.User;
import com.moviemux.user.domain.repository.UserRepository;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class PromoteUserUseCase {
  private final UserRepository repository;

  public User execute(PromoteUserCommand command) throws UserNotFoundException {
    UUID userId = command.userId();
    User user = repository.findById(userId).orElse(null);

    if (user == null) {
      throw new UserNotFoundException();
    }

    boolean isAlreadyAdm = user.getRoles().stream().anyMatch(r -> r.getDescription() == "ROLE_ADM");

    if (isAlreadyAdm) {
      return user;
    }

    user.addRole(Role.ADM);

    return repository.save(user);
  }
}
