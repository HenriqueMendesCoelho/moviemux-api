package com.moviemux.user.adapter.repository.postgres;

import com.moviemux.user.adapter.repository.UserRepository;
import com.moviemux.user.domain.repository.AccountRemovalPreparation;
import com.moviemux.user.domain.repository.UserRemovalTarget;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class UserRemovalPreparationAdapter implements AccountRemovalPreparation {
  private final UserRepository users;

  private final org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate jdbc;

  @Override
  @org.springframework.transaction.annotation.Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
  public UserRemovalTarget prepare(UUID id) {
    // Lock only this module's user row before checking cross-module history through public ports.
    // PostgreSQL FK inserts take KEY SHARE on this row, serializing creation of protected history.
    var locked = jdbc.query("select id from \"user\" where id=:id for update",
        java.util.Map.of("id", id), (row, index) -> row.getObject("id", UUID.class));
    if (locked.isEmpty()) return null;
    var user = users.getReferenceById(id);
    if (user == null) return null;
    user.getId();
    return new UserRemovalTarget() {
      @Override
      public UUID userId() {
        return id;
      }

      @Override
      public void deleteAfterMoviesDetached() {
        users.delete(user);
      }
    };
  }
}
