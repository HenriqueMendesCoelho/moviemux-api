package com.moviemux.wishlist.usecase.impl;

import java.util.List;
import java.util.UUID;

import com.moviemux.user.domain.User;
import com.moviemux.wishlist.adapter.repository.WishlistRepository;
import com.moviemux.wishlist.domain.Wishlist;
import com.moviemux.wishlist.usecase.CreateUserWishlistUseCase;
import com.moviemux.wishlist.usecase.exception.WishlistDuplicatedException;
import com.moviemux.wishlist.usecase.exception.WishlistUserReachedLimitException;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class CreateUserWishlistUseCaseImpl implements CreateUserWishlistUseCase {

	private final WishlistRepository repository;

	@Override
	public Wishlist create(String name, UUID userId)
			throws WishlistDuplicatedException, WishlistUserReachedLimitException {

		if (hasUserReachedLimit(userId)) {
			throw new WishlistUserReachedLimitException();
		}

		Wishlist wishlist = Wishlist.builder()
				.name(name)
				.shareable(false)
				.user(User.builder().id(userId).build())
				.build();

		try {
			return repository.save(wishlist);
		} catch (DataIntegrityViolationException e) {
			throw new WishlistDuplicatedException(name);
		}
	}

	private boolean hasUserReachedLimit(UUID userId) {
		List<Wishlist> wishlists = repository.findByUserIdOrderByCreatedAtDesc(userId);

		return wishlists != null ? wishlists.size() >= 10 : false;
	}

}
