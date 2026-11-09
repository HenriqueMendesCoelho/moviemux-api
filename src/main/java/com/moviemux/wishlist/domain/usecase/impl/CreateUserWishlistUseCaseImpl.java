package com.moviemux.wishlist.domain.usecase.impl;

import com.moviemux.wishlist.domain.command.*;
import com.moviemux.wishlist.domain.exception.WishlistDuplicatedException;
import com.moviemux.wishlist.domain.exception.WishlistPersistenceConflict;
import com.moviemux.wishlist.domain.exception.WishlistUserReachedLimitException;
import com.moviemux.wishlist.domain.model.Wishlist;
import com.moviemux.wishlist.domain.model.WishlistOwner;
import com.moviemux.wishlist.domain.repository.WishlistRepository;
import com.moviemux.wishlist.domain.usecase.CreateUserWishlistUseCase;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class CreateUserWishlistUseCaseImpl implements CreateUserWishlistUseCase {

  private final WishlistRepository repository;

  @Override
  public Wishlist execute(CreateWishlistCommand command)
      throws WishlistDuplicatedException, WishlistUserReachedLimitException {
    String name = command.name();
    UUID userId = command.actorId();

    if (hasUserReachedLimit(userId)) {
      throw new WishlistUserReachedLimitException();
    }

    Wishlist wishlist =
        Wishlist.builder()
            .name(name)
            .shareable(false)
            .user(new WishlistOwner(userId, null))
            .build();

    try {
      return repository.save(wishlist);
    } catch (WishlistPersistenceConflict e) {
      throw new WishlistDuplicatedException(name);
    }
  }

  private boolean hasUserReachedLimit(UUID userId) {
    List<Wishlist> wishlists = repository.findByUserIdOrderByCreatedAtDesc(userId);

    return wishlists != null ? wishlists.size() >= 10 : false;
  }
}
