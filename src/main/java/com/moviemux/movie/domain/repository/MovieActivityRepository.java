package com.moviemux.movie.domain.repository;

import java.util.List;
import java.util.UUID;

public interface MovieActivityRepository {
  record Review(Integer note, Integer runtime) {}

  record Facts(List<Review> reviews, List<UUID> ownedMovies) {
    public Facts {
      reviews = List.copyOf(reviews);
      ownedMovies = List.copyOf(ownedMovies);
    }
  }

  Facts read(UUID userId);
}
