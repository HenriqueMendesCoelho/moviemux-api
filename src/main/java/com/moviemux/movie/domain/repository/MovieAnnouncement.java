package com.moviemux.movie.domain.repository;

import java.util.UUID;

public interface MovieAnnouncement {
  void created(UUID movieId);

  void updated(UUID movieId);
}
