package com.moviemux.wishlist.domain.exception;

public class WishlistMaxedOutException extends Exception {

  private static final long serialVersionUID = -4206164321126627894L;

  public WishlistMaxedOutException(String name) {
    super("Movie already exists in wishlist %s".formatted(name));
  }
}
