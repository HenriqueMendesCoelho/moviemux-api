package com.moviemux.wishlist.domain.exception;

public class WishlistMovieAlreadyExistsException extends Exception {

  private static final long serialVersionUID = -4206164321126627894L;

  public WishlistMovieAlreadyExistsException(String name) {
    super("Wishlist %s maxed out size".formatted(name));
  }
}
