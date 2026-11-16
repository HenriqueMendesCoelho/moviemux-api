package com.moviemux.wishlist.adapter.controller;

import com.moviemux.wishlist.domain.command.FindWishlistCommand;
import com.moviemux.wishlist.domain.exception.WishlistNotFoundException;
import com.moviemux.wishlist.domain.model.Wishlist;
import com.moviemux.wishlist.domain.usecase.FindUserWishlistUseCase;
import com.moviemux.wishlist.domain.usecase.ListUserWishlistsUseCase;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class WishlistQueryOperations {
  private final FindUserWishlistUseCase find;
  private final ListUserWishlistsUseCase list;

  public WishlistQueryOperations(FindUserWishlistUseCase find, ListUserWishlistsUseCase list) {
    this.find = find;
    this.list = list;
  }

  public Wishlist findById(UUID id, UUID actor) throws WishlistNotFoundException {
    return find.execute(new FindWishlistCommand(id, actor));
  }

  public List<Wishlist> list(UUID actor) {
    return list.execute(actor);
  }
}
