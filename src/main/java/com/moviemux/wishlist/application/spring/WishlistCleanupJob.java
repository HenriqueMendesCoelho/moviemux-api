package com.moviemux.wishlist.application.spring;

import com.moviemux.wishlist.domain.usecase.DeleteMovieWishlistUseCase;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class WishlistCleanupJob {
  private final DeleteMovieWishlistUseCase cleanup;

  public WishlistCleanupJob(DeleteMovieWishlistUseCase cleanup) {
    this.cleanup = cleanup;
  }

  @Scheduled(initialDelay = 10000, fixedDelayString = "P1D")
  public void cleanup() {
    cleanup.execute();
  }
}
