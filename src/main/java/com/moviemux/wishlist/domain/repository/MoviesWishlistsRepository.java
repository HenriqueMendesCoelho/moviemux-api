package com.moviemux.wishlist.domain.repository;

import com.moviemux.wishlist.domain.model.*;
import java.util.List;

public interface MoviesWishlistsRepository {
  List<MoviesWishlists> saveAll(List<MoviesWishlists> memberships);

  void deleteAll(List<MoviesWishlists> memberships);
}
