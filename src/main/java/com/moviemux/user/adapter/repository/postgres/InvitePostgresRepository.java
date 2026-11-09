package com.moviemux.user.adapter.repository.postgres;

import com.moviemux.user.domain.model.Invite;
import com.moviemux.user.domain.repository.InviteRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class InvitePostgresRepository implements InviteRepository {
  private final com.moviemux.user.adapter.repository.InviteRepository legacy;

  private Invite map(com.moviemux.user.adapter.repository.postgres.entity.InviteEntity value) {
    return value == null ? null : new Invite(value.getId(), value.getCode());
  }

  public Invite findByCode(String code) {
    return map(legacy.findByCode(code));
  }

  public List<Invite> findAll() {
    return legacy.findAll().stream().map(this::map).toList();
  }

  public Invite save(Invite invite) {
    return map(
        legacy.saveAndFlush(new com.moviemux.user.adapter.repository.postgres.entity.InviteEntity(invite.id(), invite.code())));
  }

  public void delete(Invite invite) {
    legacy.delete(new com.moviemux.user.adapter.repository.postgres.entity.InviteEntity(invite.id(), invite.code()));
  }
}
