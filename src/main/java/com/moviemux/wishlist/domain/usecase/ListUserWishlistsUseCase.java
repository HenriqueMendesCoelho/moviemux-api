package com.moviemux.wishlist.domain.usecase;

import com.moviemux.wishlist.domain.model.Wishlist;
import com.moviemux.wishlist.domain.repository.WishlistRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class ListUserWishlistsUseCase {
  private final WishlistRepository repository;

  public ListUserWishlistsUseCase(WishlistRepository repository) {
    this.repository = repository;
  }

  public List<Wishlist> execute(UUID actorId) {
    return repository.findByUserIdOrderByCreatedAtDesc(actorId);
  }
}
