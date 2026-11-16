package com.moviemux.wishlist.domain.usecase;

import com.moviemux.movie.domain.model.MovieCatalogEntry;
import java.util.List;
import java.util.UUID;

public interface SearchMoviesAlreadyRated {

  List<MovieCatalogEntry> execute(UUID wishlistId);
}
