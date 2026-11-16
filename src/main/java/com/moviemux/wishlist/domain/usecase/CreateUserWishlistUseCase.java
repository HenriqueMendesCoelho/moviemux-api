package com.moviemux.wishlist.domain.usecase;

import com.moviemux.wishlist.domain.command.*;
import com.moviemux.wishlist.domain.exception.WishlistDuplicatedException;
import com.moviemux.wishlist.domain.exception.WishlistUserReachedLimitException;
import com.moviemux.wishlist.domain.model.Wishlist;

public interface CreateUserWishlistUseCase {

  Wishlist execute(CreateWishlistCommand command)
      throws WishlistDuplicatedException, WishlistUserReachedLimitException;
}
