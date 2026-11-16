package com.moviemux.user.domain.repository;

import java.util.UUID;

public interface UserActivityReader {
  record Activity(
      int ratingsGiven, int registeredMovies, int displayTime, double averageRatingMovies) {}

  Activity read(UUID userId);
}
