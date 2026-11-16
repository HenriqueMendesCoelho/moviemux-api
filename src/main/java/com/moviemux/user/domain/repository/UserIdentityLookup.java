package com.moviemux.user.domain.repository;

import com.moviemux.user.domain.model.UserIdentity;
import java.util.Optional;
import java.util.UUID;

public interface UserIdentityLookup {
  Optional<UserIdentity> find(UUID userId);

  Optional<UserIdentity> findByEmail(String email);
}
