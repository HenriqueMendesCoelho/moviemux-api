package com.moviemux.user.domain.usecase;

import com.moviemux.user.domain.command.ChangePasswordCommand;
import com.moviemux.user.domain.exception.UserNotAuthorizedException;
import com.moviemux.user.domain.exception.UserNotFoundException;
import com.moviemux.user.domain.model.User;
import com.moviemux.user.domain.repository.PasswordHasher;
import com.moviemux.user.domain.repository.UserRepository;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ChangePasswordUseCase {
  private final UserRepository repository;
  private final PasswordHasher passwordEncoder;

  public User execute(ChangePasswordCommand command)
      throws UserNotFoundException, UserNotAuthorizedException {
    UUID userId = command.userId();
    String password = command.password();
    String newPassword = command.newPassword();
    User user = repository.findById(userId).orElse(null);

    if (user == null) {
      throw new UserNotFoundException();
    }

    boolean passwordIsValid = passwordEncoder.matches(password, user.getPassword());

    if (!passwordIsValid) {
      throw new UserNotAuthorizedException();
    }

    user.setPassword(passwordEncoder.encode(newPassword));

    return repository.save(user);
  }
}
