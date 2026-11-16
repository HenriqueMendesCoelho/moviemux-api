package com.moviemux.wishlist.domain.repository;

import java.util.UUID;

public interface WishlistOwnershipRemoval {
  void removeOwnedBy(UUID userId);
}
