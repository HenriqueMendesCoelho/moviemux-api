package com.moviemux.wishlist.domain.exception;

/** Preserves the existing integrity-conflict mapping without leaking Spring exceptions. */
public final class WishlistPersistenceConflict extends RuntimeException {
  public WishlistPersistenceConflict(Throwable cause) {
    super(cause);
  }
}
