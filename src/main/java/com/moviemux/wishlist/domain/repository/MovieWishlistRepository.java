package com.moviemux.wishlist.domain.repository;

import com.moviemux.wishlist.domain.model.*;
import java.util.List;
import java.util.Optional;

public interface MovieWishlistRepository {
  Optional<MovieWishlist> findById(Long tmdbId);

  List<MovieWishlist> findAll();

  MovieWishlist save(MovieWishlist movie);

  void delete(MovieWishlist movie);
}
