package com.moviemux.user.adapter.repository.postgres;

import com.moviemux.user.domain.model.User;
import com.moviemux.user.domain.model.UserIdentity;
import com.moviemux.user.domain.repository.UserIdentityLookup;
import com.moviemux.user.domain.repository.UserRepository;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class UserIdentityAdapter implements UserIdentityLookup {
  private final UserRepository users;

  public Optional<UserIdentity> find(UUID id) {
    return users.findById(id).map(this::map);
  }

  public Optional<UserIdentity> findByEmail(String email) {
    return Optional.ofNullable(users.findByEmail(email)).map(this::map);
  }

  private UserIdentity map(User user) {
    return new UserIdentity(user.getId(), user.getName(), user.getEmail(), user.getRoles());
  }
}
