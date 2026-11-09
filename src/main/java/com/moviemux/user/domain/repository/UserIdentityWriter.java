package com.moviemux.user.domain.repository;

import java.util.UUID;

public interface UserIdentityWriter {
  void flush(UUID id);
}
