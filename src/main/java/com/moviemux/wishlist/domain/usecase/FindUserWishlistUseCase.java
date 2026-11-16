package com.moviemux.wishlist.domain.usecase;

import com.moviemux.wishlist.domain.command.FindWishlistCommand;
import com.moviemux.wishlist.domain.exception.WishlistNotFoundException;
import com.moviemux.wishlist.domain.model.Wishlist;
import com.moviemux.wishlist.domain.repository.WishlistRepository;
import org.springframework.stereotype.Component;

@Component
public class FindUserWishlistUseCase {
  private final WishlistRepository repository;

  public FindUserWishlistUseCase(WishlistRepository repository) {
    this.repository = repository;
  }

  public Wishlist execute(FindWishlistCommand command) throws WishlistNotFoundException {
    Wishlist wishlist = repository.findById(command.wishlistId()).orElse(null);
    if (wishlist == null
        || (!wishlist.getUser().id().equals(command.actorId()) && !wishlist.isShareable())) {
      throw new WishlistNotFoundException();
    }
    return wishlist;
  }
}
