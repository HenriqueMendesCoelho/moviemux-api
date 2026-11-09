package com.moviemux.user.domain.repository;

import java.util.UUID;

public interface AccountRemoval {
  void remove(UUID userId);
}
