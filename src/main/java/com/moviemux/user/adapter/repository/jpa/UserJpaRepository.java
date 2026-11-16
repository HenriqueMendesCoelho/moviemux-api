package com.moviemux.user.adapter.repository.jpa;

import com.moviemux.user.adapter.repository.postgres.entity.UserEntity;
import java.util.UUID;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

public interface UserJpaRepository extends JpaRepository<UserEntity, UUID> {

  @Transactional(readOnly = true)
  @EntityGraph(attributePaths = {"roles", "statistics"})
  UserEntity findByEmail(String email);

  @Transactional(readOnly = true)
  @EntityGraph(attributePaths = {"roles", "statistics"})
  UserEntity findByRedefinePasswordKey(String redefinePasswordKey);
}
