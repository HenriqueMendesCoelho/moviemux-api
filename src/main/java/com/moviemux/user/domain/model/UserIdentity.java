package com.moviemux.user.domain.model;

import com.moviemux.user.domain.Role;
import java.util.Set;
import java.util.UUID;

/** Public account projection. Authentication secrets never cross this contract. */
public record UserIdentity(UUID id, String name, String email, Set<Role> roles) {
  public UserIdentity {
    roles = Set.copyOf(roles);
  }
}
