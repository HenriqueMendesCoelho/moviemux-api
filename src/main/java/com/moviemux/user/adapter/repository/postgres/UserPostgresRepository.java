package com.moviemux.user.adapter.repository.postgres;

import com.moviemux.user.adapter.repository.postgres.mapper.UserEntityMapper;
import com.moviemux.user.domain.model.User;
import com.moviemux.user.domain.repository.UserRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
@RequiredArgsConstructor
public class UserPostgresRepository implements UserRepository {
  private final com.moviemux.user.adapter.repository.UserRepository legacy;
  private final UserEntityMapper mapper;

  public long count() {
    return legacy.count();
  }

  @Transactional(readOnly = true)
  public User findByEmail(String email) {
    return mapper.toDomain(legacy.findByEmail(email));
  }

  @Transactional(readOnly = true)
  public User findByRedefinePasswordKey(String key) {
    return mapper.toDomain(legacy.findByRedefinePasswordKey(key));
  }

  @Transactional(readOnly = true)
  public Optional<User> findById(UUID id) {
    return legacy.findById(id).map(mapper::toDomain);
  }

  @Transactional(readOnly = true)
  public List<User> findAll() {
    return legacy.findAll().stream().map(mapper::toDomain).toList();
  }

  @Transactional
  public User save(User value) {
    var target =
        value.getId() == null
            ? new com.moviemux.user.domain.User()
            : legacy.findById(value.getId()).orElseGet(com.moviemux.user.domain.User::new);
    return mapper.toDomain(legacy.saveAndFlush(mapper.updateEntity(value, target)));
  }
}
