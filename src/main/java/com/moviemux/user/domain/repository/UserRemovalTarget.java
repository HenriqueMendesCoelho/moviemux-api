package com.moviemux.user.domain.repository;

import java.util.UUID;

/** Operation-scoped handle. The caller completes it inside the account-removal transaction. */
public interface UserRemovalTarget {
  UUID userId();

  void deleteAfterMoviesDetached();
}
