package com.moviemux.wishlist.domain.model;

import java.util.UUID;

public record WishlistOwner(UUID id, String name) {
  public UUID getId() {
    return id;
  }

  public String getName() {
    return name;
  }
}
