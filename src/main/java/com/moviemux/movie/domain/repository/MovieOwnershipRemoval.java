package com.moviemux.movie.domain.repository;

import java.util.UUID;

public interface MovieOwnershipRemoval {
  void detach(UUID userId);
}
