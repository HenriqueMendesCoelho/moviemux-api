package com.moviemux.movie.domain.repository;

import java.util.UUID;

/** Protected history must not be destroyed before the explicit identity migration exists. */
@FunctionalInterface
public interface MovieAccountHistory {
  boolean hasProtectedHistory(UUID userId);
}
