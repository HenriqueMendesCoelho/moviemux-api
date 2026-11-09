package com.moviemux.wishlist.domain.exception;

public class WishlistNotFoundException extends ReflectiveOperationException {

  private static final long serialVersionUID = 2800307934478206153L;

  public WishlistNotFoundException() {
    super("wishlist not found");
  }
}
