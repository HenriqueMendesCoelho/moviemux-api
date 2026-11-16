package com.moviemux.user.domain.repository;

import com.moviemux.user.domain.model.User;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface UserRepository {
  long count();

  User findByEmail(String email);

  User findByRedefinePasswordKey(String key);

  Optional<User> findById(UUID id);

  List<User> findAll();

  User save(User user);
}
