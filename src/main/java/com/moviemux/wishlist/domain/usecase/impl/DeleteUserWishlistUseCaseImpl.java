package com.moviemux.wishlist.domain.usecase.impl;

import com.moviemux.wishlist.domain.command.*;
import com.moviemux.wishlist.domain.model.Wishlist;
import com.moviemux.wishlist.domain.repository.MoviesWishlistsRepository;
import com.moviemux.wishlist.domain.repository.WishlistRepository;
import com.moviemux.wishlist.domain.usecase.DeleteUserWishlistUseCase;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Transactional(rollbackFor = Exception.class)
public class DeleteUserWishlistUseCaseImpl implements DeleteUserWishlistUseCase {

  private final WishlistRepository repository;

  private final MoviesWishlistsRepository moviesWishlistsRepository;

  @Override
  public void execute(DeleteWishlistCommand command) {
    UUID wishlistId = command.wishlistId();
    UUID userId = command.actorId();
    Wishlist wishlist = repository.findById(wishlistId).orElse(null);
    if (wishlist == null || !wishlist.getUser().getId().equals(userId)) {
      return;
    }
    moviesWishlistsRepository.deleteAll(wishlist.getMoviesWishlists());
    repository.delete(wishlist);
  }
}
