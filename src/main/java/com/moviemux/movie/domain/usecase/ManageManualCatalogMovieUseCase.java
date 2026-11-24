package com.moviemux.movie.domain.usecase;

import com.moviemux.movie.domain.model.ManualCanonicalMovie.Admin;
import com.moviemux.movie.domain.model.ManualCanonicalMovie.Command;
import com.moviemux.movie.domain.model.ManualCanonicalMovie.Result;

public interface ManageManualCatalogMovieUseCase {
  void requireCurrentAdmin(Admin actor);
  Result create(Admin actor, Command command);
}
