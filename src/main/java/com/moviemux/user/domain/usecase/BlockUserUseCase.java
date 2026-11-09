package com.moviemux.user.domain.usecase;

import com.moviemux.user.domain.command.BlockUserCommand;
import com.moviemux.user.domain.exception.UserNotFoundException;
import com.moviemux.user.domain.model.User;
import com.moviemux.user.domain.repository.UserRepository;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class BlockUserUseCase {
  private final UserRepository repository;

  public User execute(BlockUserCommand command) throws UserNotFoundException {
    UUID userId = command.userId();
    User user = repository.findById(userId).orElse(null);

    if (user == null) {
      throw new UserNotFoundException();
    }

    user.getStatistics().setConsecutiveFailedLoginAttempts(10);

    return repository.save(user);
  }
}
