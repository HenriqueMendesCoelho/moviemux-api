package com.moviemux.user.domain.repository;

import com.moviemux.user.domain.model.Invite;
import java.util.List;

public interface InviteRepository {
  Invite findByCode(String code);

  List<Invite> findAll();

  Invite save(Invite invite);

  void delete(Invite invite);
}
