package com.moviemux.wishlist.adapter.controller;

import com.moviemux.user.domain.exception.UserNotAuthorizedException;
import com.moviemux.wishlist.domain.command.AddWishlistMovieCommand;
import com.moviemux.wishlist.domain.command.UpdateWishlistCommand;
import com.moviemux.wishlist.domain.exception.WishlistMovieAlreadyExistsException;
import com.moviemux.wishlist.domain.exception.WishlistNotFoundException;
import com.moviemux.wishlist.domain.model.Wishlist;
import com.moviemux.wishlist.domain.usecase.AddWishlistMovieUseCase;
import com.moviemux.wishlist.domain.usecase.UpdateWishlistUseCase;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class WishlistUpdateOperations {
  private final UpdateWishlistUseCase update;
  private final AddWishlistMovieUseCase add;

  public WishlistUpdateOperations(UpdateWishlistUseCase update, AddWishlistMovieUseCase add) {
    this.update = update;
    this.add = add;
  }

  public Wishlist update(Wishlist wishlist, UUID actor)
      throws WishlistNotFoundException,
          WishlistMovieAlreadyExistsException,
          UserNotAuthorizedException {
    return update.execute(new UpdateWishlistCommand(wishlist, actor));
  }

  public Wishlist addMovieToWishlist(UUID id, UUID actor, Long tmdbId)
      throws WishlistNotFoundException, WishlistMovieAlreadyExistsException {
    return add.execute(new AddWishlistMovieCommand(id, actor, tmdbId));
  }
}
