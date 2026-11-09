package com.moviemux.user.adapter.repository.jpa;

import com.moviemux.user.domain.Invite;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

public interface InviteJpaRepository extends JpaRepository<Invite, Long> {

  @Transactional(readOnly = true)
  Invite findByCode(String code);
}
