package com.moviemux.wishlist.domain.exception;

/** A recognized wishlist uniqueness conflict, translated by its persistence adapter. */
public final class WishlistPersistenceConflict extends RuntimeException {
  public WishlistPersistenceConflict(Throwable cause) {
    super(cause);
  }
}
