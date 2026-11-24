package com.moviemux.movie.domain.repository;

import com.moviemux.movie.domain.model.ManualCanonicalMovie.Command;
import com.moviemux.movie.domain.model.ManualCanonicalMovie.Result;
import java.util.UUID;

public interface ManualCatalogMovies {
  boolean currentAdmin(UUID actorId);

  /** Recheck and lock current global authority before inserting or reporting any command outcome. */
  Result create(UUID actorId, Command normalized, String fingerprint);
}
