package com.moviemux.wishlist.domain.usecase.impl;

import com.moviemux.wishlist.domain.command.*;
import com.moviemux.wishlist.domain.model.MovieWishlist;
import com.moviemux.wishlist.domain.repository.MovieWishlistRepository;
import com.moviemux.wishlist.domain.usecase.DeleteMovieWishlistUseCase;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
@Transactional(rollbackFor = Exception.class)
public class DeleteMovieWishlistUseCaseImpl implements DeleteMovieWishlistUseCase {

  private final MovieWishlistRepository repository;

  @Override
  public void execute() {
    List<MovieWishlist> movies = repository.findAll();

    log.info("Started the validation process for deleting movies from the wishlist");

    int num = 0;
    for (MovieWishlist movie : movies) {
      if (!movie.isReferenced()) {
        num++;
        repository.delete(movie);
      }
    }

    log.info("%s movies wishlist deleted".formatted(num));
  }
}
