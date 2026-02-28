package com.moviemux.wishlist.usecase.impl;

import java.util.UUID;

import com.moviemux.wishlist.adapter.repository.MoviesWishlistsRepository;
import com.moviemux.wishlist.adapter.repository.WishlistRepository;
import com.moviemux.wishlist.domain.Wishlist;
import com.moviemux.wishlist.usecase.DeleteUserWishlistUseCase;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class DeleteUserWishlistUseCaseImpl implements DeleteUserWishlistUseCase {

	private final WishlistRepository repository;
	private final MoviesWishlistsRepository moviesWishlistsRepository;

	@Override
	public void delete(UUID wishlistId, UUID userId) {
		Wishlist wishlist = repository.findById(wishlistId).orElse(null);
		if (wishlist == null || !wishlist.getUser().getId().equals(userId)) {
			return;
		}
		moviesWishlistsRepository.deleteAll(wishlist.getMoviesWishlists());
		repository.delete(wishlist);

	}

}
