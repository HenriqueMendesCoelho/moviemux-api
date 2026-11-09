package com.moviemux.user.domain.repository;

import java.util.List;
import java.util.UUID;

/** Operation-scoped handle. The caller completes it inside the account-removal transaction. */
public interface UserRemovalTarget {
  List<UUID> ownedMovieIds();

  void deleteAfterMoviesDetached();
}
