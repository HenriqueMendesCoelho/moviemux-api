package com.moviemux.wishlist.domain.usecase.impl;

import com.moviemux.movie.domain.model.MovieCatalogEntry;
import com.moviemux.movie.domain.repository.MovieReadLookup;
import com.moviemux.wishlist.domain.model.Wishlist;
import com.moviemux.wishlist.domain.repository.WishlistRepository;
import com.moviemux.wishlist.domain.usecase.SearchMoviesAlreadyRated;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.apache.commons.collections4.CollectionUtils;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class SearchMoviesAlreadyRatedImpl implements SearchMoviesAlreadyRated {

  private final WishlistRepository repository;

  private final MovieReadLookup movieRepository;

  @Override
  public List<MovieCatalogEntry> execute(UUID wishlistId) {
    Wishlist wishlist = repository.findById(wishlistId).orElse(null);
    if (wishlist == null || CollectionUtils.isEmpty(wishlist.getMoviesWishlists())) {
      return new ArrayList<>();
    }
    List<MovieCatalogEntry> moviesRated = movieRepository.listAll();

    return moviesRated.stream()
        .filter(
            m ->
                wishlist.getMoviesWishlists().stream()
                    .anyMatch(mw -> mw.getMovieWishlist().getTmdbId().equals(m.tmdbId())))
        .toList();
  }
}
