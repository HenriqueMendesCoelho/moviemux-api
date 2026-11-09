package com.moviemux.wishlist.domain.repository;

import com.moviemux.wishlist.domain.model.*;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface WishlistRepository {
  Optional<Wishlist> findById(UUID id);

  List<Wishlist> findByUserIdOrderByCreatedAtDesc(UUID userId);

  Wishlist save(Wishlist wishlist);

  void delete(Wishlist wishlist);
}
