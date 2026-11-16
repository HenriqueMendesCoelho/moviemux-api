package com.moviemux.user.adapter.repository.postgres;

import com.moviemux.user.adapter.repository.UserRepository;
import com.moviemux.user.domain.repository.UserIdentityWriter;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class UserIdentityWriterPostgresAdapter implements UserIdentityWriter {
  private final UserRepository users;

  public void flush(UUID id) {
    users.saveAndFlush(users.findById(id).orElse(null));
  }
}
