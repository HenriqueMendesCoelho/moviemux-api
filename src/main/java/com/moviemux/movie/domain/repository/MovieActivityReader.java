package com.moviemux.movie.domain.repository;

import java.util.UUID;

public interface MovieActivityReader {
  record Activity(
      int ratingsGiven, int registeredMovies, int displayTime, double averageRatingMovies) {}

  Activity read(UUID userId);
}
