package com.moviemux.user.adapter.repository.jpa;

import com.moviemux.user.adapter.repository.postgres.entity.InviteEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

public interface InviteJpaRepository extends JpaRepository<InviteEntity, Long> {

  @Transactional(readOnly = true)
  InviteEntity findByCode(String code);
}
