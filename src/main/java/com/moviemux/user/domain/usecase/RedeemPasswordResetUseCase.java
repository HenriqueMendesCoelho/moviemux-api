package com.moviemux.user.domain.usecase;

import com.moviemux.user.domain.exception.UserNotAuthorizedException;
import com.moviemux.user.domain.exception.UserRedefinePasswordKeyInvalid;
import com.moviemux.user.domain.exception.UserRedefinePasswordKeyNotFound;
import com.moviemux.user.domain.model.User;
import com.moviemux.user.domain.repository.PasswordHasher;
import com.moviemux.user.domain.repository.UserRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class RedeemPasswordResetUseCase {
  private final UserRepository repository;
  private final Clock clock;
  private final PasswordHasher passwordEncoder;

  public User execute(com.moviemux.user.domain.command.RedeemPasswordResetCommand command)
      throws UserRedefinePasswordKeyNotFound,
          UserRedefinePasswordKeyInvalid,
          UserNotAuthorizedException {
    String redefinePasswordKey = command.key(),
        email = command.email(),
        password = command.password();

    User user = repository.findByRedefinePasswordKey(redefinePasswordKey);

    if (user == null) {
      throw new UserRedefinePasswordKeyNotFound();
    }

    if (!email.equalsIgnoreCase(user.getEmail())) {
      throw new UserNotAuthorizedException("Email provided not known with target user");
    }

    Duration duration =
        Duration.between(user.getRedefinePasswordKeyCreatedAt(), OffsetDateTime.now(clock));
    long hours = duration.toHours();
    if (hours >= 1) {
      cleanRedefinePassowordKeyAndSave(user);
      throw new UserRedefinePasswordKeyInvalid();
    }

    user.setPassword(passwordEncoder.encode(password));
    return cleanRedefinePassowordKeyAndSave(user);
  }

  private User cleanRedefinePassowordKeyAndSave(User user) {
    user.setRedefinePasswordKey(null);
    user.setRedefinePasswordKeyCreatedAt(null);
    return repository.save(user);
  }
}
