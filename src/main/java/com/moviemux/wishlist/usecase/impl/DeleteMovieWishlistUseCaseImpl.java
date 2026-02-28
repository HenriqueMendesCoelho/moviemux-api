package com.moviemux.wishlist.usecase.impl;

import java.util.List;

import com.moviemux.wishlist.adapter.repository.MovieWishlistRepository;
import com.moviemux.wishlist.domain.MovieWishlist;
import com.moviemux.wishlist.usecase.DeleteMovieWishlistUseCase;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class DeleteMovieWishlistUseCaseImpl implements DeleteMovieWishlistUseCase {

	private final MovieWishlistRepository repository;

	@Scheduled(initialDelay = 10000, fixedDelayString = "P1D")
	@Override
	public void delete() {
		List<MovieWishlist> movies = repository.findAll();

		log.info("Started the validation process for deleting movies from the wishlist");

		int num = 0;
		for (MovieWishlist movie : movies) {
			if (movie.getWishlists() == null || movie.getWishlists().isEmpty()) {
				num++;
				repository.delete(movie);
			}
		}

		log.info("%s movies wishlist deleted".formatted(num));
	}

}
