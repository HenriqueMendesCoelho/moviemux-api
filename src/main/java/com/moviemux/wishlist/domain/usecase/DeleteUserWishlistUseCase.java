package com.moviemux.wishlist.domain.usecase;

import com.moviemux.wishlist.domain.command.*;

public interface DeleteUserWishlistUseCase {

  void execute(DeleteWishlistCommand command);
}
