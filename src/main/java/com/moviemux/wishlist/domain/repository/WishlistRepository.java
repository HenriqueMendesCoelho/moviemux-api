package com.moviemux.wishlist.domain.repository;

import com.moviemux.wishlist.domain.model.*;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface WishlistRepository {
  Optional<Wishlist> findById(UUID id);

  /** Acquires the wishlist write lock before loading its current memberships. */
  Optional<Wishlist> findByIdForUpdate(UUID id);

  /** Serializes quota decisions for this owner until the caller transaction completes. */
  void lockOwner(UUID ownerId);

  List<Wishlist> findByUserIdOrderByCreatedAtDesc(UUID userId);

  Wishlist save(Wishlist wishlist);

  void delete(Wishlist wishlist);
}
