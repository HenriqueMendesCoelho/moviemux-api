package com.kronusboss.cine.wishlist.usecase.impl;

import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.kronusboss.cine.wishlist.adapter.repository.jpa.WishlistRepository;
import com.kronusboss.cine.wishlist.domain.Wishlist;
import com.kronusboss.cine.wishlist.usecase.DeleteUserWishlistUseCase;

@Component
public class DeleteUserWishlistUseCaseImpl implements DeleteUserWishlistUseCase {

	@Autowired
	private WishlistRepository repository;

	@Override
	public void delete(UUID wishlistId, UUID userId) {
		Wishlist wishlist = repository.findById(wishlistId).orElse(null);

		if (wishlist == null || !wishlist.getUser().getId().equals(userId)) {
			return;
		}

		repository.delete(wishlist);
	}

}
