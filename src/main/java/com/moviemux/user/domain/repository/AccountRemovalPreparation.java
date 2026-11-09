package com.moviemux.user.domain.repository;

import java.util.UUID;

public interface AccountRemovalPreparation {
  UserRemovalTarget prepare(UUID userId);
}
