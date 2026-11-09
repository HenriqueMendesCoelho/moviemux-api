package com.moviemux.movie.domain.repository;

import java.util.List;
import java.util.UUID;

public interface MovieOwnershipRemoval {
  void detach(List<UUID> movieIds);
}
